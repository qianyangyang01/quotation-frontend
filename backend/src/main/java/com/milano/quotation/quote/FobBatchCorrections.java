package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** Revisions live alongside review metadata. The original quotation payload is never overwritten. */
@RestController
@RequestMapping("/api/v1/quotations/{id}/fob-corrections")
public class FobBatchCorrections {
    private final QuotationRecordRepository records;
    private final QuotationReviewRepository reviews;
    private final FobQuotation pricing;
    private final QuotationReviewNotifications notifications;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    public FobBatchCorrections(QuotationRecordRepository records, QuotationReviewRepository reviews, FobQuotation pricing,
            QuotationReviewNotifications notifications, AuditService audit, IdempotencyService idempotency) {
        this.records=records; this.reviews=reviews; this.pricing=pricing; this.notifications=notifications; this.audit=audit; this.idempotency=idempotency;
    }
    static JsonNode snapshot(QuotationRecordEntity quote, JsonNode state) {
        return state.has("fobRevisionSnapshot") ? state.path("fobRevisionSnapshot") : quote.payload.path("fob");
    }
    static ArrayNode itemStates(JsonNode snapshot, JsonNode state) {
        if(state.path("fobItemReviews").isArray()) return (ArrayNode)state.path("fobItemReviews").deepCopy();
        var result=JsonNodeFactory.instance.arrayNode();
        String initial=Set.of("approved","logistics-exempt").contains(state.path("financeReviewStatus").asText())?"approved":"pending";
        for(int i=0;i<snapshot.path("items").size();i++) result.addObject().put("index",i).put("status",initial).put("reason","");
        return result;
    }
    private QuotationRecordEntity lock(UUID id) {
        var quote=records.lockById(id).orElseThrow(()->AppException.notFound("报价记录不存在"));
        QuotationLifecycleController.assertActive(quote);
        if(quote.payload.at("/fob/schemaVersion").asInt()!=2) throw AppException.unprocessable("仅批量FOB报价支持逐项修正");
        return quote;
    }
    private QuotationReviewEntity state(QuotationRecordEntity quote) {
        return reviews.findById(quote.id).orElseGet(()->{
            var row=new QuotationReviewEntity();row.id=quote.id;row.state=QuotationReviewService.legacy(quote.payload);
            row.status=row.state.path("financeReviewStatus").asText("pending");return reviews.saveAndFlush(row);
        });
    }
    static void version(QuotationRecordEntity quote, QuotationReviewEntity review, JsonNode body) {
        if(!body.path("_version").isIntegralNumber() || body.path("_version").asLong()!=quote.version
                || !body.path("_reviewVersion").isIntegralNumber() || body.path("_reviewVersion").asLong()!=review.version)
            throw AppException.conflict("报价或审核已变化，请刷新记录后重新处理；当前内容未提交");
    }
    static List<Integer> indexes(JsonNode entries,int size) {
        if(!entries.isArray()||entries.isEmpty()||entries.size()>100)throw AppException.unprocessable("请选择1–100项商品");
        var result=new ArrayList<Integer>();
        for(var entry:entries) {
            var index=entry.path("index");
            if(!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<0||index.asInt()>=size||result.contains(index.asInt()))
                throw AppException.unprocessable("商品索引不存在或重复，请刷新记录");
            result.add(index.asInt());
        }
        return result;
    }
    @PostMapping("/review")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE') and hasAuthority('PERM_allRecords')")
    @Transactional
    public ApiResponse<JsonNode> review(@PathVariable UUID id,@RequestBody ObjectNode body,Authentication auth) {
        var quote=lock(id);var row=state(quote);version(quote,row,body);var actor=(QuotationPrincipal)auth.getPrincipal();
        if(!row.status.equals("reviewing")||!actor.account().equals(row.claimantAccount))throw AppException.conflict("请先开始审核，只有当前审核人可以处理商品");
        var current=(ObjectNode)row.state.deepCopy();var items=itemStates(snapshot(quote,current),current);
        var selected=indexes(body.path("items"),items.size());var action=body.path("action").asText();
        if(!Set.of("return","approve").contains(action))throw AppException.unprocessable("请选择退回或审核通过");
        if(body.has("approveOthers")&&!body.path("approveOthers").isBoolean())throw AppException.unprocessable("审核参数无效");
        int offset=0;
        for(int index:selected) {
            var item=(ObjectNode)items.get(index);var entry=body.path("items").get(offset++);
            if(action.equals("approve")&&item.path("status").asText().equals("rejected"))throw AppException.conflict("退回项必须由业务员重新查询提交后再审核");
            String reason=action.equals("return")?FobQuotation.text(entry.path("reason"),500,true).trim():"";
            item.put("status",action.equals("return")?"rejected":"approved").put("reason",reason)
                .put("reviewedBy",actor.displayName()).put("reviewedAt",Instant.now().toString());
        }
        if(action.equals("return")&&body.path("approveOthers").asBoolean(false)) for(int i=0;i<items.size();i++) {
            var item=(ObjectNode)items.get(i);
            if(!selected.contains(i)&&item.path("status").asText().equals("pending"))item.put("status","approved").put("reviewedBy",actor.displayName()).put("reviewedAt",Instant.now().toString());
        }
        current.set("fobItemReviews",items);
        finish(quote,row,current,actor,action.equals("return")?"fob-return":"fob-approve",selected.size()+"项"+(action.equals("return")?"已退回，请查看各SKU自定义原因":"审核通过"),null,selected);
        return ApiResponse.ok(view(quote,row));
    }
    @PostMapping("/submit")
    @PreAuthorize("hasAuthority('PERM_quote') and hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    public ApiResponse<JsonNode> submit(@PathVariable UUID id,@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        if(body.toString().length()>4_000_000)throw AppException.unprocessable("修正数据过大，请分批提交");
        var quote=lock(id);var actor=(QuotationPrincipal)auth.getPrincipal();
        if(!quote.ownerAccount.equals(actor.account()))throw new AccessDeniedException("只能修正自己的报价");
        String operation="quotation-fob-correction-"+id;
        var cached=idempotency.existing(actor.account(),operation,key,body);if(cached.isPresent())return ApiResponse.ok(cached.get());
        var row=state(quote);version(quote,row,body);
        if(row.status.equals("reviewing"))throw AppException.conflict("管理员正在审核，请等待本轮审核结束后提交");
        var current=(ObjectNode)row.state.deepCopy();var previous=snapshot(quote,current);var merged=(ObjectNode)previous.deepCopy();
        var states=itemStates(previous,current);var selected=indexes(body.path("items"),states.size());
        var changes=JsonNodeFactory.instance.arrayNode();int offset=0;
        for(int index:selected) {
            if(!states.get(index).path("status").asText().equals("rejected"))throw AppException.conflict("只能重新提交当前退回的商品，请刷新记录");
            var old=previous.path("items").get(index);var submitted=body.path("items").get(offset++).path("snapshot");
            if(submitted.path("schemaVersion").asInt()!=1 || !submitted.at("/product/sku").equals(old.at("/product/sku"))
                    || !submitted.at("/product/source").equals(old.at("/product/source")) || !submitted.path("displayMode").equals(old.path("displayMode")))
                throw AppException.unprocessable("不能替换退回商品的SKU、采购来源或报价方式");
            var request=FobQuotation.object().put("quoteMode","fob");request.set("customerName",quote.payload.path("customerName"));request.set("fob",submitted);
            // Validate prices and lock source/finance again at commit time; client totals are never trusted.
            var fresh=pricing.prepare(request).path("fob");
            if(!sourceChanged(old,fresh))throw AppException.conflict(old.at("/product/sku").asText()+"：资料、数量和计价参数未变化，仍需核实后重新查询");
            ((ArrayNode)merged.path("items")).set(index,fresh);
            ((ObjectNode)states.get(index)).put("status","pending").put("resubmittedAt",Instant.now().toString());
            changes.addObject().put("index",index).set("snapshot",fresh.deepCopy());
        }
        rebuildSheet(merged);
        current.set("fobItemReviews",states);current.set("fobRevisionSnapshot",merged);
        current.put("fobRevision",current.path("fobRevision").asInt(0)+1);
        finish(quote,row,current,actor,"fob-resubmit",selected.size()+"项重新查询后提交复核",changes,selected);
        var result=view(quote,row);idempotency.save(actor.account(),operation,key,body,result);return ApiResponse.ok(result);
    }
    static boolean sourceChanged(JsonNode before,JsonNode after) {
        return !before.at("/product/updatedAt").equals(after.at("/product/updatedAt")) || before.path("quantity").asLong()!=after.path("quantity").asLong()
                || FobQuotation.amount(before.path("rate")).compareTo(FobQuotation.amount(after.path("rate")))!=0
                || !before.at("/policy/scope").equals(after.at("/policy/scope")) || !before.at("/policy/calculation").equals(after.at("/policy/calculation"));
    }
    static void rebuildSheet(ObjectNode snapshot) {
        var sheet=(ObjectNode)snapshot.path("sheet");var previousRows=sheet.path("rows").deepCopy();
        var rows=JsonNodeFactory.instance.arrayNode();var identities=new HashSet<String>();int i=0;
        for(var item:snapshot.path("items")) {
            String identity=item.at("/product/sku").asText()+":"+(item.path("displayMode").asText().equals("tiers")?"tiers":item.path("quantity").asText());
            if(!identities.add(identity))throw AppException.unprocessable("修改数量后与其他商品行重复，请调整数量");
            for(var source:item.at("/sheet/rows")) {
                var row=(ObjectNode)source.deepCopy();row.put("key",i+":"+row.path("key").asText()).put("number",rows.size()+1);
                if(!item.at("/sheet/quantityLabels").equals(sheet.path("quantityLabels"))) {
                    var prices=row.putArray("prices");prices.add(source.path("prices").get(1));prices.add(source.path("prices").get(0));
                }
                rows.add(row);
            }
            i++;
        }
        if(rows.size()>2000)throw AppException.unprocessable("合并报价超过2000行阶梯，当前修正未提交");
        // Keep saved presentation and row ordering, including reversed declaration columns.
        var remaining=new LinkedHashMap<String,JsonNode>();
        for(var row:rows)remaining.put(row.path("key").asText(),row);
        var ordered=sheet.putArray("rows");
        for(var old:previousRows) {
            var fresh=remaining.remove(old.path("key").asText());
            if(fresh!=null) {
                var retained=(ObjectNode)old.deepCopy();
                retained.set("prices",fresh.path("prices"));retained.set("quantityRange",fresh.path("quantityRange"));
                ordered.add(retained);
            }
        }
        remaining.values().forEach(ordered::add);
        for(int n=0;n<ordered.size();n++)((ObjectNode)ordered.get(n)).put("number",n+1);
        var oldNotes=sheet.path("notes").deepCopy();var notes=sheet.putArray("notes");
        for(var note:oldNotes) {
            String text=note.asText();
            for(var item:snapshot.path("items"))text=text.replaceAll(java.util.regex.Pattern.quote(item.at("/product/sku").asText())+": Order in multiples of [0-9]+\\.","");
            if(!text.isBlank())notes.add(text.trim());
        }
        StringBuilder multiples=new StringBuilder();
        for(var item:snapshot.path("items")) {
            long multiple=item.at("/product/parsed/orderMultiple").asLong(1);
            if(multiple<=1)continue;
            String note=item.at("/product/sku").asText()+": Order in multiples of "+multiple+". ";
            if(multiples.length()+note.length()>2900){notes.add(multiples.toString().trim());multiples.setLength(0);}
            multiples.append(note);
        }
        if(!multiples.isEmpty())notes.add(multiples.toString().trim());
        if(notes.size()>20)throw AppException.unprocessable("合并报价说明过多，请联系管理员核查，当前修正未提交");
    }
    private void finish(QuotationRecordEntity quote,QuotationReviewEntity row,ObjectNode current,QuotationPrincipal actor,String action,String note,ArrayNode changes,List<Integer> selected) {
        var states=current.path("fobItemReviews");boolean rejected=false,pending=false;
        for(var item:states){rejected|=item.path("status").asText().equals("rejected");pending|=item.path("status").asText().equals("pending");}
        String before=row.status;row.status=pending?"pending":rejected?"rejected":"approved";row.claimantAccount=null;
        current.remove(List.of("financeReviewClaimedAccount","financeReviewClaimedBy","financeReviewStartedAt"));
        current.put("financeReviewStatus",row.status).put("financeReviewNote",note).put("priorityProcessing",false);
        if(!action.equals("fob-resubmit"))current.put("financeReviewedBy",actor.displayName()).put("financeReviewedAccount",actor.account()).put("financeReviewedAt",Instant.now().toString());
        QuotationReviewService.event(current,actor,action,before,row.status,note,quote.version);
        ((ObjectNode)current.path("history").get(current.path("history").size()-1)).set("itemReviews",current.path("fobItemReviews").deepCopy());
        if(changes!=null){var event=(ObjectNode)current.path("history").get(current.path("history").size()-1);event.set("items",changes);event.put("revision",current.path("fobRevision").asInt());}
        var details=((ObjectNode)current.path("history").get(current.path("history").size()-1)).putArray("itemChanges");
        for(int index:selected)details.addObject().put("index",index).put("sku",snapshot(quote,current).path("items").get(index).at("/product/sku").asText())
            .put("status",states.get(index).path("status").asText()).put("reason",states.get(index).path("reason").asText());
        row.state=current;reviews.saveAndFlush(row);
        if(rejected||!pending)notifications.publish(quote,row,rejected?"fob-return":"complete",rejected&&pending?note+"；仍有退回项待处理，已提交项进入待审核列表":note,actor);else notifications.invalidate(quote.id);
        audit.record("quotation.review."+action,"quotation",quote.id.toString(),"success",Map.of("note",note,"revision",current.path("fobRevision").asInt()));
    }
    static ObjectNode view(QuotationRecordEntity quote,QuotationReviewEntity row) {
        var value=(ObjectNode)quote.payload.deepCopy();value.put("id",quote.id.toString());value.put("_version",quote.version).put("lifecycleState",quote.lifecycleState);
        QuotationReviewService.overlay(value,row.state,row.version);return value;
    }
    @GetMapping("/revisions/{revision}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<JsonNode> revision(@PathVariable UUID id,@PathVariable int revision,Authentication auth) {
        var quote=records.findById(id).orElseThrow(()->AppException.notFound("报价不存在"));
        if(!quote.ownerAccount.equals(((QuotationPrincipal)auth.getPrincipal()).account())&&auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("PERM_allRecords")))throw new AccessDeniedException("forbidden");
        var state=reviews.findById(id).map(r->r.state).orElse(FobQuotation.object());
        if(quote.payload.at("/fob/schemaVersion").asInt()!=2||revision<0||revision>state.path("fobRevision").asInt())throw AppException.notFound("修订版本不存在");
        var snapshot=(ObjectNode)quote.payload.path("fob").deepCopy();
        for(var event:state.path("history"))if(event.path("action").asText().equals("fob-resubmit")&&event.path("revision").asInt()<=revision)
            for(var change:event.path("items"))((ArrayNode)snapshot.path("items")).set(change.path("index").asInt(),change.path("snapshot").deepCopy());
        if(revision>0)rebuildSheet(snapshot);return ApiResponse.ok(snapshot);
    }
}

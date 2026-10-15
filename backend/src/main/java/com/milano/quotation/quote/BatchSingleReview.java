package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** Locks the immutable parent to serialize claim, review, correction and idempotent retries. */
@RestController
@RequestMapping("/api/v1/batch-single-quotations/{id}")
public class BatchSingleReview {
    @org.springframework.beans.factory.annotation.Autowired private PriceExceptionClassification exceptions;
    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final BatchSingleQuotation pricing;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    public BatchSingleReview(JdbcClient jdbc, JsonMapper mapper, BatchSingleQuotation pricing, IdempotencyService idempotency, AuditService audit) {
        this.jdbc=jdbc;this.mapper=mapper;this.pricing=pricing;this.idempotency=idempotency;this.audit=audit;
    }
    record Stored(ObjectNode payload,String owner,ObjectNode state,long version) {}
    private Stored read(UUID id, QuotationPrincipal user, boolean lock) {
        var parent=jdbc.sql("select payload::text,owner_account from batch_single_quotation where id=:id"+(lock?" for update":""))
            .param("id",id).query((rs,n)->new Stored((ObjectNode)mapper.readTree(rs.getString(1)),rs.getString(2),null,0)).optional()
            .orElseThrow(()->AppException.notFound("批量报价不存在或无权查看"));
        if(!parent.owner.equals(user.account())&&!user.permissions().contains("allRecords"))throw AppException.notFound("批量报价不存在或无权查看");
        return jdbc.sql("select state::text,version from batch_single_review where record_id=:id").param("id",id)
            .query((rs,n)->new Stored(parent.payload,parent.owner,(ObjectNode)mapper.readTree(rs.getString(1)),rs.getLong(2))).optional()
            .orElseGet(()->new Stored(parent.payload,parent.owner,initial(parent.payload),0));
    }
    static ObjectNode initial(JsonNode payload) {
        var state=JsonNodeFactory.instance.objectNode().put("status","pending").put("revision",0);
        var items=state.putArray("items");for(int i=0;i<payload.path("items").size();i++)items.addObject().put("index",i).put("status","pending");
        state.putArray("history");return state;
    }
    static ObjectNode decorate(ObjectNode payload,String owner,ObjectNode state,long version) {
        var out=payload.deepCopy();
        if(state.has("current")){out.set("items",state.at("/current/items").deepCopy());out.set("sheet",state.at("/current/sheet").deepCopy());}
        var review=state.deepCopy();review.remove("current");
        out.put("ownerAccount",owner).put("_reviewVersion",version).set("review",review);return out;
    }
    ObjectNode view(UUID id,QuotationPrincipal user) { var stored=read(id,user,false);return decorate(stored.payload,stored.owner,stored.state,stored.version); }
    @GetMapping("/original")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<JsonNode> original(@PathVariable UUID id,Authentication auth) {return ApiResponse.ok(read(id,(QuotationPrincipal)auth.getPrincipal(),false).payload);}
    private void version(JsonNode body,Stored stored) {
        if(!body.path("_reviewVersion").isIntegralNumber()||!body.path("_reviewVersion").canConvertToLong()||body.path("_reviewVersion").asLong()!=stored.version)
            throw AppException.conflict("报价审核已变化，请重新加载后操作");
    }
    private static List<Integer> indexes(JsonNode input,int count) {
        if(!input.isArray()||input.isEmpty()||input.size()>50)throw AppException.unprocessable("请选择1至50个商品");
        var result=new ArrayList<Integer>();
        for(var item:input){var index=item.path("index");if(!index.isIntegralNumber()||!index.canConvertToInt()||index.asInt()<0||index.asInt()>=count||result.contains(index.asInt()))throw AppException.unprocessable("商品序号无效或重复");result.add(index.asInt());}
        return result;
    }
    private static void release(ObjectNode state) {state.remove(List.of("claimantAccount","claimantName","claimedAt"));state.put("status",aggregate(state.path("items")));}
    private static String aggregate(JsonNode items) {
        boolean pending=false,rejected=false;var results=new HashSet<String>();
        for(var item:items){pending|=item.path("status").asText().equals("pending");rejected|=item.path("status").asText().equals("rejected");results.add(item.path("reviewResult").asText("approved"));}
        return pending?"pending":rejected?"rejected":results.size()==1?results.iterator().next():"approved";
    }
    private ObjectNode save(UUID id,Stored stored,ObjectNode state,QuotationPrincipal actor,String action,ArrayNode changes,String note) {
        var event=state.withArray("history").addObject().put("id",UUID.randomUUID().toString()).put("action",action).put("actorAccount",actor.account())
            .put("actorName",actor.displayName()).put("at",Instant.now().toString()).put("note",note).put("revision",state.path("revision").asInt())
            .put("beforeStatus",stored.state.path("status").asText()).put("status",state.path("status").asText());
        event.set("items",changes);
        jdbc.sql("insert into batch_single_review(record_id,version,state) values(:id,:version,cast(:state as jsonb)) on conflict(record_id) do update set version=excluded.version,state=excluded.state,updated_at=now()")
            .param("id",id).param("version",stored.version+1).param("state",state.toString()).update();
        audit.record("batch-single."+action,"batch-single-quotation",id.toString(),"success",Map.of("reviewVersion",stored.version+1,"itemCount",changes.size()));
        return decorate(stored.payload,stored.owner,state,stored.version+1);
    }
    @PostMapping("/review")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE') and hasAuthority('PERM_allRecords')")
    @Transactional
    public ApiResponse<JsonNode> review(@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        if(!body.isObject()||body.toString().length()>100_000)throw AppException.unprocessable("审核请求无效");
        var actor=(QuotationPrincipal)auth.getPrincipal();var stored=read(id,actor,true);String operation="batch-single-review:"+id;
        var old=idempotency.existing(actor.account(),operation,key,body);if(old.isPresent())return ApiResponse.ok(old.get());
        version(body,stored);var state=stored.state.deepCopy();String action=body.path("action").asText();
        var changes=JsonNodeFactory.instance.arrayNode();String note=body.has("note")?FobQuotation.text(body.path("note"),500,false).trim():"";
        if(action.equals("claim")) {
            if(state.path("status").asText().equals("reviewing"))throw AppException.conflict(state.path("claimantName").asText()+"正在审核此报价");
            state.put("status","reviewing").put("claimantAccount",actor.account()).put("claimantName",actor.displayName()).put("claimedAt",Instant.now().toString());
        } else {
            if(!state.path("status").asText().equals("reviewing")||!state.path("claimantAccount").asText().equals(actor.account()))throw AppException.conflict("请先领取审核；仅当前审核人可以操作");
            if(action.equals("cancel"))release(state);
            else if(action.equals("complete")) {
                if(body.path("approveOthers").asBoolean())throw AppException.unprocessable("只允许处理明确选中的SKU");
                String result=body.path("result").asText();if(!Set.of("approved","rejected","logistics-exempt","channel-exempt").contains(result))throw AppException.unprocessable("请选择审核结论");
                var selected=indexes(body.path("items"),state.path("items").size());int offset=0;
                for(int index:selected) {
                    var item=(ObjectNode)state.path("items").get(index);var entry=body.path("items").get(offset++);
                    if(!result.equals("rejected")&&item.path("status").asText().equals("rejected"))throw AppException.conflict("退回项须重新查询修正并提交后才能通过");
                    String reason=result.equals("rejected")?PriceExceptionClassification.optionalExplanation(entry,"reason"):"";
                    var before=item.deepCopy();item.put("status",result.equals("rejected")?"rejected":"approved").put("reviewResult",result).put("reason",reason).put("note",note)
                        .put("reviewedBy",actor.displayName()).put("reviewedAccount",actor.account()).put("reviewedAt",Instant.now().toString());
                    if(result.equals("rejected"))item.set("priceException",exceptions.captureItem(entry,stored.payload.at("/items/"+index+"/input/primarySku").asText(),"standard"));
                    else item.remove("priceException");
                    var change=changes.addObject().put("index",index).put("sku",stored.payload.at("/items/"+index+"/input/primarySku").asText());change.set("before",before);change.set("after",item.deepCopy());
                }
                release(state);
            } else throw AppException.unprocessable("审核操作无效");
        }
        var result=save(id,stored,state,actor,action,changes,note);idempotency.save(actor.account(),operation,key,body,result);return ApiResponse.ok(result);
    }
    @PostMapping("/corrections")
    @PreAuthorize("hasAuthority('PERM_quote') and hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(timeout=120)
    public ApiResponse<JsonNode> correct(@PathVariable UUID id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        if(!body.isObject()||body.toString().length()>4_100_000)throw AppException.unprocessable("修正请求过大或无效");
        var actor=(QuotationPrincipal)auth.getPrincipal();var stored=read(id,actor,true);
        if(!stored.owner.equals(actor.account())&&!actor.roleKey().equals("super_admin"))throw new AccessDeniedException("仅报价本人或超级管理员可修正");
        String operation="batch-single-correct:"+id;var old=idempotency.existing(actor.account(),operation,key,body);if(old.isPresent())return ApiResponse.ok(old.get());
        version(body,stored);if(stored.state.path("status").asText().equals("reviewing"))throw AppException.conflict("审核中不能修正，请等待审核人完成或取消");
        var state=stored.state.deepCopy();var selected=indexes(body.path("items"),state.path("items").size());
        var current=state.has("current")?(ObjectNode)state.path("current").deepCopy():stored.payload.deepCopy();
        // Server validates current procurement, customer pricing and published logistics again.
        var fresh=pricing.prepare(body.path("document"));
        if(fresh.path("items").size()!=selected.size()||!fresh.at("/sheet/quantityLabels").equals(current.at("/sheet/quantityLabels")))throw AppException.unprocessable("修正商品或数量列与原报价不一致");
        var changes=JsonNodeFactory.instance.arrayNode();int offset=0;
        for(int index:selected) {
            var item=(ObjectNode)state.path("items").get(index);if(!item.path("status").asText().equals("rejected"))throw AppException.conflict("仅允许修正已退回商品");
            var before=current.path("items").get(index);var after=fresh.path("items").get(offset++);
            for(String field:List.of("primarySku","customerName","customerGrade","commissionThreshold"))if(!before.path("input").path(field).equals(after.path("input").path(field)))throw AppException.unprocessable("修正时须保留原SKU、客户、等级及佣金条件");
            if(before.equals(after))throw AppException.conflict("报价资料和价格未变化，请核实并重新查询后提交");
            var change=changes.addObject().put("index",index).put("sku",before.at("/input/primarySku").asText());change.set("before",before.deepCopy());change.set("after",after.deepCopy());change.set("previousReview",item.deepCopy());
            ((ArrayNode)current.path("items")).set(index,after.deepCopy());
            item.remove(List.of("reviewResult","reason","note","reviewedBy","reviewedAccount","reviewedAt"));item.put("status","pending").put("resubmittedAt",Instant.now().toString());
        }
        var sheet=(ObjectNode)current.path("sheet");var rows=sheet.putArray("rows");int i=0;
        for(var item:current.path("items")){for(var row:item.at("/sheet/rows")){var copy=(ObjectNode)row.deepCopy();copy.put("key",i+":"+row.path("key").asText()).put("number",rows.size()+1);rows.add(copy);}i++;}
        if(rows.size()>500)throw AppException.unprocessable("修正后超过500条渠道明细");
        state.set("current",current);state.put("revision",state.path("revision").asInt()+1);release(state);
        var result=save(id,stored,state,actor,"resubmit",changes,"修正所选SKU并提交复核");idempotency.save(actor.account(),operation,key,body,result);return ApiResponse.ok(result);
    }
}

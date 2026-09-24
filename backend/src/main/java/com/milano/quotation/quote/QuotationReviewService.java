package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import com.milano.quotation.security.QuotationPrincipal;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="quotation_review")
class QuotationReviewEntity {
    @Id UUID id;
    @Column(nullable=false, length=16) String status;
    @Column(name="claimant_account", length=24) String claimantAccount;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false, columnDefinition="jsonb") JsonNode state;
    @Version long version;
}
interface QuotationReviewRepository extends JpaRepository<QuotationReviewEntity, UUID> {}

/** All writes lock the quotation first, including price edits. Claiming never dirties its payload/version. */
@Service
public class QuotationReviewService {
    static final Set<String> VIEW_FIELDS = Set.of("financeReviewStatus", "financeReviewedAt", "financeReviewedBy", "financeReviewedAccount",
        "financeReviewStartedAt", "financeReviewClaimedBy", "financeReviewClaimedAccount", "financeReviewNote");
    private final QuotationReviewRepository reviews;
    private final AuditService audit;
    public QuotationReviewService(QuotationReviewRepository reviews, AuditService audit) { this.reviews=reviews; this.audit=audit; }
    String currentStatus(QuotationRecordEntity quote) {
        return reviews.findById(quote.id).map(row->row.status).orElseGet(()->legacy(quote.payload).path("financeReviewStatus").asText());
    }

    static ObjectNode legacy(JsonNode payload) {
        var state=JsonNodeFactory.instance.objectNode();
        for (var key:VIEW_FIELDS) if(payload.has(key)) state.set(key,payload.get(key));
        if(!Set.of("approved","rejected").contains(state.path("financeReviewStatus").asText())) state.put("financeReviewStatus","pending");
        return state;
    }
    static void overlay(ObjectNode payload, JsonNode state, long version) {
        VIEW_FIELDS.forEach(payload::remove);
        for (var key:VIEW_FIELDS) if(state.has(key)) payload.set(key,state.get(key));
        payload.put("_reviewVersion",version);
    }
    void enrich(List<JsonNode> payloads) {
        var ids=payloads.stream().map(p->UUID.fromString(p.path("id").asText())).toList();
        var found=new HashMap<UUID,QuotationReviewEntity>();
        reviews.findAllById(ids).forEach(row->found.put(row.id,row));
        for(var payload:payloads) {
            var row=found.get(UUID.fromString(payload.path("id").asText()));
            overlay((ObjectNode)payload,row==null?legacy(payload):row.state,row==null?0:row.version);
        }
    }
    JsonNode history(QuotationRecordEntity quote) {
        return reviews.findById(quote.id).map(row->row.state.has("history")?row.state.get("history").deepCopy():(JsonNode)JsonNodeFactory.instance.arrayNode()).orElse(JsonNodeFactory.instance.arrayNode());
    }
    private QuotationReviewEntity state(QuotationRecordEntity quote) {
        return reviews.findById(quote.id).orElseGet(()->{
            var row=new QuotationReviewEntity();row.id=quote.id;row.state=legacy(quote.payload);
            row.status=row.state.path("financeReviewStatus").asText();return reviews.saveAndFlush(row);
        });
    }
    void change(QuotationRecordEntity quote, ObjectNode request, QuotationPrincipal actor) {
        var row=state(quote);var action=request.path("action").asText();
        if (!request.path("_reviewVersion").isIntegralNumber() || request.path("_reviewVersion").asLong(-1)!=row.version)
            throw AppException.conflict("审核状态已变化，请刷新后重试");
        var current=(ObjectNode)row.state.deepCopy();var before=row.status;
        preserveLegacyHistory(quote,current);
        var note=request.path("note").asText("").trim();
        if (note.length()>500) throw AppException.unprocessable("审核备注不能超过500字");
        if(action.equals("claim")) {
            if(row.status.equals("reviewing")) throw AppException.conflict("该报价已由"+current.path("financeReviewClaimedBy").asText()+"审核中");
            requireQuoteVersion(quote,request);
            VIEW_FIELDS.forEach(current::remove);
            row.status="reviewing";row.claimantAccount=actor.account();
            current.put("financeReviewClaimedAccount",actor.account()).put("financeReviewClaimedBy",actor.displayName())
                .put("financeReviewStartedAt",Instant.now().toString());
        } else {
            if(!row.status.equals("reviewing")) throw AppException.conflict("请先开始审核，当前报价未被领取");
            if(action.equals("release")) {
                if(!actor.roleKey().equals("super_admin")) throw new AccessDeniedException("只有超级管理员可以解除他人占用");
                if(note.isBlank()) throw AppException.unprocessable("请填写解除占用原因");
            } else if(!actor.account().equals(row.claimantAccount)) throw new AccessDeniedException("只有当前审核人可以取消或完成审核");
            if(action.equals("complete")) {
                requireQuoteVersion(quote,request);
                var result=request.path("financeReviewStatus").asText();
                if(!Set.of("approved","rejected").contains(result)) throw AppException.unprocessable("请选择审核结论");
                row.status=result;
                current.put("financeReviewedBy",actor.displayName()).put("financeReviewedAccount",actor.account()).put("financeReviewedAt",Instant.now().toString());
                current.put("financeReviewNote",note);
            } else row.status="pending";
            row.claimantAccount=null;
            current.remove(List.of("financeReviewClaimedAccount","financeReviewClaimedBy","financeReviewStartedAt"));
        }
        current.put("financeReviewStatus",row.status);
        event(current,actor,action,before,row.status,note,quote.version);
        row.state=current;reviews.saveAndFlush(row);
        audit.record("quotation.review."+action,"quotation",quote.id.toString(),"success",Map.of("before",before,"after",row.status,"note",note));
    }
    private void requireQuoteVersion(QuotationRecordEntity quote,ObjectNode request) {
        if(!request.path("_version").isIntegralNumber() || request.path("_version").asLong(-1)!=quote.version)
            throw AppException.conflict("报价内容已更新，请重新打开详情核对后审核");
    }
    void contentChanged(QuotationRecordEntity quote, QuotationPrincipal actor) {
        var row=state(quote);
        if(row.status.equals("pending")) return;
        var current=(ObjectNode)row.state.deepCopy();var before=row.status;
        preserveLegacyHistory(quote,current);
        if(!row.status.equals("reviewing")) {
            row.status="pending";row.claimantAccount=null;VIEW_FIELDS.forEach(current::remove);
            current.put("financeReviewStatus","pending");
        }
        event(current,actor,"content-changed",before,row.status,"报价内容已变化，需重新核对",quote.version+1);
        row.state=current;reviews.saveAndFlush(row);
        audit.record("quotation.review.content-changed","quotation",quote.id.toString(),"success",Map.of("before",before,"after",row.status));
    }
    void withdrawn(QuotationRecordEntity quote, QuotationPrincipal actor) {
        var row=state(quote);var current=(ObjectNode)row.state.deepCopy();var before=row.status;
        preserveLegacyHistory(quote,current);
        VIEW_FIELDS.forEach(current::remove);
        row.status="pending";row.claimantAccount=null;current.put("financeReviewStatus","pending");
        event(current,actor,"withdraw",before,"pending","报价已撤回，原审核失效",quote.version+1);
        row.state=current;reviews.saveAndFlush(row);
    }
    private static void event(ObjectNode state,QuotationPrincipal actor,String action,String before,String after,String note,long quoteVersion) {
        var event=state.withArray("history").addObject().put("id",UUID.randomUUID().toString()).put("action",action).put("before",before).put("after",after)
            .put("actorAccount",actor.account()).put("actorName",actor.displayName()).put("at",Instant.now().toString()).put("note",note);
        // Match JSON deserialization's numeric node type so Hibernate's snapshots remain equal.
        // A small LongNode round-trips as IntNode, otherwise every flush increments the review version.
        if (quoteVersion >= Integer.MIN_VALUE && quoteVersion <= Integer.MAX_VALUE) event.put("quoteVersion",(int)quoteVersion);
        else event.put("quoteVersion",quoteVersion);
    }
    private static void preserveLegacyHistory(QuotationRecordEntity quote,ObjectNode state) {
        if(state.has("history") || !Set.of("approved","rejected").contains(state.path("financeReviewStatus").asText()))return;
        state.withArray("history").addObject().put("id",quote.id+"-legacy").put("action","legacy-review").put("before","pending")
            .put("after",state.path("financeReviewStatus").asText()).put("actorAccount",state.path("financeReviewedAccount").asText("历史记录未保存"))
            .put("actorName",state.path("financeReviewedBy").asText("历史审核人未保存")).put("at",state.path("financeReviewedAt").asText(""))
            .put("note","保留的历史审核结果");
    }
}

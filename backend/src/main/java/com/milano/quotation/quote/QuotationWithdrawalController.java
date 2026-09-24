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

/** Withdrawal is a transaction, never a client-side copy followed by a status update. */
@RestController @RequestMapping("/api/v1/quotations")
public class QuotationWithdrawalController {
    private final QuotationRecordRepository records;
    private final QuotationDraftRepository drafts;
    private final QuotationReviewRepository reviewRows;
    private final QuotationReviewService reviews;
    private final QuotationDraftGuard guard;
    private final QuotationDraftController draftApi;
    private final QuotationSubmissionValidator validator;
    private final QuotationReadinessService readiness;
    private final com.milano.quotation.logistics.LogisticsQuotationGuard logistics;
    private final com.milano.quotation.purchase.PurchaseProductService products;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final QuotationCountryIndex countries;
    private final QuotationAnalyticsController analytics;
    public QuotationWithdrawalController(QuotationRecordRepository records, QuotationDraftRepository drafts,
        QuotationReviewRepository reviewRows, QuotationReviewService reviews, QuotationDraftGuard guard,
        QuotationDraftController draftApi, QuotationSubmissionValidator validator, QuotationReadinessService readiness,
        com.milano.quotation.logistics.LogisticsQuotationGuard logistics, com.milano.quotation.purchase.PurchaseProductService products,
        IdempotencyService idempotency, AuditService audit, org.springframework.jdbc.core.simple.JdbcClient jdbc,
        QuotationCountryIndex countries, QuotationAnalyticsController analytics) {
        this.records=records;this.drafts=drafts;this.reviewRows=reviewRows;this.reviews=reviews;this.guard=guard;
        this.draftApi=draftApi;this.validator=validator;this.readiness=readiness;this.logistics=logistics;this.products=products;
        this.idempotency=idempotency;this.audit=audit;this.jdbc=jdbc;this.countries=countries;this.analytics=analytics;
    }

    @PostMapping("/{id}/withdraw") @PreAuthorize("hasAuthority('PERM_quote') and hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> withdraw(@PathVariable UUID id,@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        var actor=actor(auth);guard.lock(actor.account());
        var operation="quotation-withdraw:"+id;
        if(idempotency.existing(actor.account(),operation,key,body).isPresent()) {
            var draft=drafts.findById(actor.account()).filter(d->id.equals(d.sourceQuoteId))
                .orElseThrow(()->AppException.conflict("本次撤回已处理，请刷新草稿状态"));
            return ApiResponse.ok(draftApi.view(draft));
        }
        var quote=owned(id,actor,body);QuotationLifecycleController.assertActive(quote);requireNoDeal(quote);
        if(drafts.existsById(actor.account()))throw AppException.conflict("已有草稿，请先完成或放弃现有草稿，再撤回报价");
        var payload=draftApi.validated(body.path("draft").deepCopy());products.lockStructuredReferences(payload);
        reviews.withdrawn(quote,actor);
        quote.lifecycleState="withdrawn";quote.updatedAt=Instant.now();
        var old=(ObjectNode)quote.payload.deepCopy();old.put("updatedAt",quote.updatedAt.toString());quote.payload=old;
        records.saveAndFlush(quote);
        var draft=new QuotationDraftEntity();draft.ownerAccount=actor.account();draft.payload=payload;draft.updatedAt=Instant.now();
        draft.sourceQuoteId=id;draft.sourceQuoteVersion=quote.version;drafts.saveAndFlush(draft);
        idempotency.save(actor.account(),operation,key,body,JsonNodeFactory.instance.objectNode().put("id",id.toString()));
        audit.record("quotation.withdraw","quotation",id.toString(),"success",Map.of("quoteNo",quote.quoteNo));
        invalidateAfterCommit(id);
        return ApiResponse.ok(draftApi.view(draft));
    }

    @PostMapping("/{id}/cancel") @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> cancel(@PathVariable UUID id,@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        var actor=actor(auth);guard.lock(actor.account());var operation="quotation-cancel:"+id;
        var previous=idempotency.existing(actor.account(),operation,key,body);if(previous.isPresent())return ApiResponse.ok(previous.get());
        var quote=owned(id,actor,body);requireNoDeal(quote);
        if(!Set.of("active","withdrawn").contains(quote.lifecycleState))throw AppException.conflict("只能取消当前报价或本人撤回草稿");
        var draft=drafts.findById(actor.account()).orElse(null);
        if(quote.lifecycleState.equals("withdrawn")) {
            requireDraft(quote,draft,body);drafts.delete(draft);drafts.flush();
        }
        reviewRows.findById(id).ifPresent(reviewRows::delete);reviewRows.flush();
        jdbc.sql("delete from logistics_quotation_history where quotation_id=:id").param("id",id).update();
        idempotency.eraseQuotationResponses(actor.account(),id);
        // No quotation/review body survives in the operation journal.
        jdbc.sql("delete from audit_log where resource_type='quotation' and resource_id=:id").param("id",id.toString()).update();
        records.delete(quote);records.flush();
        audit.record("quotation.cancel","quotation",id.toString(),"success",Map.of("quoteNo",quote.quoteNo));
        var response=JsonNodeFactory.instance.objectNode().put("cancelled",true);
        idempotency.save(actor.account(),operation,key,body,response);invalidateAfterCommit(id);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{id}/resubmit") @PreAuthorize("hasAuthority('PERM_quote') and hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> resubmit(@PathVariable UUID id,@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth) {
        var actor=actor(auth);guard.lock(actor.account());var operation="quotation-resubmit:"+id;
        var previous=idempotency.existing(actor.account(),operation,key,body);if(previous.isPresent())return ApiResponse.ok(previous.get());
        var quote=owned(id,actor,body);requireNoDeal(quote);
        var draft=drafts.findById(actor.account()).orElse(null);requireDraft(quote,draft,body);
        if(!(body.path("quotation") instanceof ObjectNode input)||input.toString().length()>4_000_000)throw AppException.unprocessable("报价数据格式错误或过大");
        // This new endpoint has no legacy clients: never fall back to unversioned pricing.
        if(!input.path("purchaseVersions").isObject()||input.path("purchaseVersions").isEmpty()
            ||!input.path("financeVersions").isObject()||input.path("financeVersions").isEmpty())
            throw AppException.unprocessable("缺少采购或财务版本信息，请刷新草稿并重新计价");
        var payload=input.deepCopy();validator.validate(payload);validator.validateQuotePricing(payload);readiness.assertCanCreate(payload);
        logistics.validate(payload);
        payload.remove(List.of("customerId","_version","_reviewVersion","lifecycleState","lifecyclePreviousState","lifecycleChangedAt","lifecycleChangedBy","lifecycleChangedAccount","lifecycleReason",
            "actualQuoteUsd","actualQuoteCny","dealQuantity","dealLines","dealOptionId","dealOptionLabel","closedAt","note","quoteConfirmedAt","quoteConfirmedBy"));
        var now=Instant.now();payload.put("id",id.toString()).put("no",quote.quoteNo).put("salespersonAccount",quote.ownerAccount)
            .put("salespersonName",quote.payload.path("salespersonName").asText(actor.displayName()))
            .put("createdAt",quote.createdAt.toString()).put("updatedAt",now.toString()).put("status","pending").put("quoteConfirmed",false);
        CustomerQuotePrices.initialize(payload);QuotationFinanceReview.initialize(payload);
        var before=quote.payload.deepCopy();((ObjectNode)before).remove("revisions");
        var after=payload.deepCopy();after.remove("revisions");
        payload.set("revisions",quote.payload.path("revisions").isArray()?quote.payload.path("revisions").deepCopy():JsonNodeFactory.instance.arrayNode());
        payload.withArray("revisions").addObject().put("id",UUID.randomUUID().toString()).put("changedAt",now.toString())
            .put("editorName",actor.displayName()).put("editorAccount",actor.account()).put("field","quoteRevision").put("fieldLabel","撤回重新编辑")
            .put("before",before.toString()).put("after",after.toString());
        quote.payload=payload;quote.status="pending";quote.lifecycleState="active";quote.updatedAt=now;records.saveAndFlush(quote);
        drafts.delete(draft);drafts.flush();
        var result=payload.deepCopy();result.put("_version",quote.version).put("lifecycleState","active");reviews.enrich(List.of(result));
        idempotency.save(actor.account(),operation,key,body,result);
        audit.record("quotation.resubmit","quotation",id.toString(),"success",Map.of("quoteNo",quote.quoteNo,"version",quote.version));
        invalidateAfterCommit(id);return ApiResponse.ok(result);
    }
    private QuotationRecordEntity owned(UUID id,QuotationPrincipal actor,ObjectNode body) {
        var row=records.lockById(id).orElseThrow(()->AppException.notFound("报价已取消或不存在，请刷新"));
        if(!row.ownerAccount.equals(actor.account()))throw new AccessDeniedException("只能操作自己的报价");
        if(!body.path("_version").isIntegralNumber()||body.path("_version").asLong(-1)!=row.version)throw AppException.conflict("报价已变化，请刷新后重试");
        return row;
    }
    private static void requireNoDeal(QuotationRecordEntity row) {
        if(row.status.equals("won")||row.payload.path("dealLines").size()>0||row.payload.path("dealQuantity").asDouble()>0)
            throw AppException.conflict("已成交或已有成交明细的报价不能取消或撤回");
    }
    private static void requireDraft(QuotationRecordEntity quote,QuotationDraftEntity draft,ObjectNode body) {
        if(!quote.lifecycleState.equals("withdrawn")||draft==null||!quote.id.equals(draft.sourceQuoteId)||draft.sourceQuoteVersion==null||draft.sourceQuoteVersion!=quote.version)
            throw AppException.conflict("撤回草稿关联已变化，请重新加载");
        if(!body.path("draftVersion").isIntegralNumber()||body.path("draftVersion").asLong(-1)!=draft.version)
            throw AppException.conflict("草稿已在其他页面更新，请重新加载");
    }
    private void invalidateAfterCommit(UUID id) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
            @Override public void afterCommit(){countries.invalidate(id);analytics.invalidate(id);}
        });
    }
    private static QuotationPrincipal actor(Authentication auth){return (QuotationPrincipal)auth.getPrincipal();}
}

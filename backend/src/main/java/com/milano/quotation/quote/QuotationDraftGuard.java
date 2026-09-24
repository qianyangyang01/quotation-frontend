package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Lock the account even when no draft exists. Lock order: account, idempotency, quote, review. */
@Component
class QuotationDraftGuard {
    private final JdbcClient jdbc;
    private final QuotationDraftRepository drafts;
    QuotationDraftGuard(JdbcClient jdbc, QuotationDraftRepository drafts) { this.jdbc=jdbc; this.drafts=drafts; }
    void lock(String account) {
        jdbc.sql("select id from app_user where account=:account for update").param("account",account).query(UUID.class).list();
    }
    void requireOrdinary(String account) {
        lock(account);
        if(drafts.findById(account).map(d->d.sourceQuoteId!=null).orElse(false))
            throw AppException.conflict("请先完成撤回报价的编辑，或选择放弃编辑并取消报价");
    }
    void requireSource(QuotationDraftEntity draft, String source) {
        if (!java.util.Objects.equals(draft.sourceQuoteId==null?null:draft.sourceQuoteId.toString(),source))
            throw AppException.conflict("草稿关联已变化，请重新加载；不能覆盖撤回报价草稿");
    }
}

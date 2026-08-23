package com.milano.quotation.quote;

import com.milano.quotation.common.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

@RestController
@RequestMapping("/api/public/v1/quotation-shares")
public class PublicQuotationShareController {
    private final QuotationShareRepository shares;
    private final QuotationRecordRepository quotations;
    private final QuotationDocumentService documents;

    public PublicQuotationShareController(QuotationShareRepository shares, QuotationRecordRepository quotations,
                                          QuotationDocumentService documents) {
        this.shares = shares; this.quotations = quotations; this.documents = documents;
    }

    @GetMapping("/{token}")
    @Transactional(readOnly = true)
    ApiResponse<JsonNode> view(@PathVariable String token) {
        if (token.length() < 32 || token.length() > 128) throw AppException.notFound("分享不存在或已失效");
        var share = shares.findByTokenHash(QuotationController.sha256(token))
                .filter(item -> item.revokedAt == null && item.expiresAt.isAfter(Instant.now()))
                .orElseThrow(() -> AppException.notFound("分享不存在或已失效"));
        var quotation = quotations.findById(share.quotationId).orElseThrow(() -> AppException.notFound("报价不存在"));
        return ApiResponse.ok(documents.customerView(quotation));
    }
}

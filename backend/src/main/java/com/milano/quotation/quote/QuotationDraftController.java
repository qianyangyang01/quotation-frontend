package com.milano.quotation.quote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/api/v1/quotation-drafts") @PreAuthorize("hasAuthority('PERM_quote')")
public class QuotationDraftController {
    private final QuotationDraftRepository drafts; public QuotationDraftController(QuotationDraftRepository drafts){this.drafts=drafts;}
    @GetMapping("/mine") @Transactional(readOnly=true) ApiResponse<JsonNode> get(Authentication auth){return ApiResponse.ok(drafts.findById(account(auth)).map(row->row.payload).orElse(null));}
    @PutMapping("/mine") @Transactional ApiResponse<JsonNode> put(@RequestBody JsonNode body,Authentication auth){var account=account(auth);var row=drafts.findById(account).orElseGet(()->{var draft=new QuotationDraftEntity();draft.ownerAccount=account;return draft;});row.payload=body.deepCopy();if(row.payload instanceof ObjectNode payload)payload.remove("customerId");row.updatedAt=Instant.now();drafts.save(row);return ApiResponse.ok(row.payload);}
    @DeleteMapping("/mine") @Transactional ApiResponse<Void> delete(Authentication auth){drafts.deleteById(account(auth));return ApiResponse.ok(null);}
    private static String account(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
}

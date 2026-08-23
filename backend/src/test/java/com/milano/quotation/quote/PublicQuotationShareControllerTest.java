package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PublicQuotationShareControllerTest {
    private QuotationShareRepository shares;private QuotationRecordRepository quotations;private QuotationDocumentService documents;private PublicQuotationShareController controller;
    @BeforeEach void setup(){shares=mock(QuotationShareRepository.class);quotations=mock(QuotationRecordRepository.class);documents=mock(QuotationDocumentService.class);controller=new PublicQuotationShareController(shares,quotations,documents);}
    @Test void rejectsMalformedRevokedAndExpiredTokens(){assertThrows(AppException.class,()->controller.view("short"));assertThrows(AppException.class,()->controller.view("x".repeat(129)));var token="a".repeat(40);var share=share();share.revokedAt=Instant.now();when(shares.findByTokenHash(anyString())).thenReturn(Optional.of(share));assertThrows(AppException.class,()->controller.view(token));share.revokedAt=null;share.expiresAt=Instant.now().minusSeconds(1);assertThrows(AppException.class,()->controller.view(token));}
    @Test void rejectsMissingQuotationAndReturnsOnlyCustomerView(){var token="b".repeat(40);var share=share();when(shares.findByTokenHash(anyString())).thenReturn(Optional.of(share));when(quotations.findById(share.quotationId)).thenReturn(Optional.empty());assertThrows(AppException.class,()->controller.view(token));var quotation=new QuotationRecordEntity();quotation.id=share.quotationId;when(quotations.findById(share.quotationId)).thenReturn(Optional.of(quotation));var publicView=JsonNodeFactory.instance.objectNode().put("quoteNo","Q-1");when(documents.customerView(quotation)).thenReturn(publicView);assertSame(publicView,controller.view(token).data());}
    private QuotationShareEntity share(){var row=new QuotationShareEntity();row.id=UUID.randomUUID();row.quotationId=UUID.randomUUID();row.expiresAt=Instant.now().plusSeconds(3600);return row;}
}

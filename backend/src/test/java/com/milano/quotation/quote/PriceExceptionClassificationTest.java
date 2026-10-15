package com.milano.quotation.quote;

import com.milano.quotation.purchase.*;
import com.milano.quotation.fob.*;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceExceptionClassificationTest {
    final PurchaseProductRepository purchases=mock(PurchaseProductRepository.class);
    final FobPurchaseRepository fob=mock(FobPurchaseRepository.class);
    final PriceExceptionClassification service=new PriceExceptionClassification(purchases,fob);
    ObjectNode request(String category) { return JsonNodeFactory.instance.objectNode().put("exceptionType",category).put("note","请核实采购价").put("reason","请核实采购价"); }
    ObjectNode single() { return JsonNodeFactory.instance.objectNode().put("quoteMode","single").put("primarySku","SKU1"); }
    @Test void rejectsMissingUnknownTypes() {
        for(String type:new String[]{"","invented"}) assertThrows(AppException.class,()->service.capture(request(type),single()));
        verifyNoInteractions(purchases,fob);
    }
    @Test void acceptsMissingBlankAndNullExplanationsButRejectsInvalidOrOverlongText() {
        for(String value:new String[]{"","   "}) {
            assertEquals("purchase-price",service.capture(request("purchase-price").put("note",value),single()).path("category").asText());
            assertEquals("purchase-price",service.captureItem(request("purchase-price").put("reason",value),"SKU1","standard").path("category").asText());
        }
        var missing=JsonNodeFactory.instance.objectNode().put("exceptionType","other");
        assertDoesNotThrow(()->service.capture(missing,single()));
        assertDoesNotThrow(()->service.captureItem(missing,"SKU1","standard"));
        assertDoesNotThrow(()->service.capture(missing.putNull("note"),single()));
        assertDoesNotThrow(()->service.captureItem(missing.putNull("reason"),"SKU1","standard"));
        assertThrows(AppException.class,()->service.capture(request("other").put("note","a".repeat(501)),single()));
        assertThrows(AppException.class,()->service.captureItem(request("other").put("reason",42),"SKU1","standard"));
    }
    @Test void capturesOwnerAtReviewTimeWithoutTrustingClientOwnerOrMutatingSources() {
        var product=mock(PurchaseProduct.class);product.payload=JsonNodeFactory.instance.objectNode().put("quotationOwner","采购甲").put("dataSource","legacy_2026");
        when(purchases.findBySku("SKU1")).thenReturn(Optional.of(product));
        var quote=single();var before=quote.deepCopy();
        var result=service.capture(request("purchase-price").put("procurementOwner","伪造归属"),quote);
        assertEquals("采购甲",result.at("/targets/0/procurementOwner").asText());
        assertEquals("legacy_2026",result.at("/targets/0/dataSource").asText());
        ((ObjectNode)product.payload).put("quotationOwner","采购乙");
        assertEquals("采购甲",result.at("/targets/0/procurementOwner").asText());assertEquals(before,quote);
        verify(purchases,never()).save(any());verifyNoInteractions(fob);
    }
    @Test void bundleRequiresExactSelectedSkusAndNeverAttributesOtherMembers() {
        var quote=single().put("quoteMode","bundle");quote.putArray("bundleItems").addObject().put("sku","SKU1");quote.withArray("bundleItems").addObject().put("sku","SKU2");
        assertThrows(AppException.class,()->service.capture(request("purchase-price"),quote));
        var request=request("purchase-price");request.putArray("exceptionSkus").add("UNRELATED");
        assertThrows(AppException.class,()->service.capture(request,quote));
        request.putArray("exceptionSkus").add("SKU2");
        var result=service.capture(request,quote);assertEquals(1,result.path("targets").size());assertEquals("SKU2",result.at("/targets/0/sku").asText());
        assertEquals("unassigned",result.at("/targets/0/attribution").asText());verify(purchases,never()).findBySku("SKU1");
        request.withArray("exceptionSkus").add("SKU2");assertThrows(AppException.class,()->service.capture(request,quote));
    }
    @Test void sourceIsolationAndNonProcurementCategoriesDoNotBlamePurchase() {
        var product=new FobPurchaseProduct();product.payload=JsonNodeFactory.instance.objectNode().put("quotationOwner","FOB采购");
        when(fob.findById("SKU1")).thenReturn(Optional.of(product));
        var result=service.captureItem(request("purchase-price"),"SKU1","fob");assertEquals("FOB采购",result.at("/targets/0/procurementOwner").asText());verifyNoInteractions(purchases);
        for(String type:new String[]{"channel-price","entry-error","other"})assertTrue(service.capture(request(type),single()).path("targets").isEmpty());
        verify(fob,times(1)).findById("SKU1");
    }
}

package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsQuotationGuard;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchSingleQuotationTest {
    static ObjectNode input(int count) {
        var root=JsonNodeFactory.instance.objectNode().put("schemaVersion",1).put("customerName","客户A");
        var items=root.putArray("items"); ObjectNode merged=null;
        for(int i=0;i<count;i++) {
            var sku="SKU"+i;var item=items.addObject();var in=item.putObject("input").put("quoteMode","single").put("primarySku",sku)
                .put("customerName","客户A").put("customerGrade","S级客户").put("exchangeRate",6.7).put("commissionThreshold",1).put("customQuoteQuantity",3);
            in.putArray("quoteOptions").addObject().put("id","option").put("quoteSheetKey","channel").put("quote1Usd",5).put("quote2Usd",10).put("quote3Usd",15);
            var customer=in.putObject("customerQuote");customer.putArray("quantities").add(1).add(2).add(3);
            customer.putArray("rows").addObject().put("optionId","option").putArray("prices").add(5).add(10).add(15);
            var sheet=item.putObject("sheet").put("agent","QA").put("date","11 Oct 2026");sheet.putArray("issues");sheet.putArray("quantityLabels").add("1 pc").add("2 pcs").add("3 pcs");
            var row=sheet.putArray("rows").addObject().put("key","channel").put("number",1).put("sku",sku).put("country","United States").put("provider","4PX").put("shippingTime","7-12 working days").put("sourceDescription","");row.putArray("prices").add(5).add(10).add(15);
            if(merged==null){merged=sheet.deepCopy();merged.putArray("rows");}
            merged.withArray("rows").add(row.deepCopy().put("key",i+":channel").put("number",i+1));
        }
        if(merged!=null)root.set("sheet",merged);
        return root;
    }
    static BatchSingleQuotation service() { return new BatchSingleQuotation(mock(QuotationSubmissionValidator.class),mock(QuotationReadinessService.class),mock(LogisticsQuotationGuard.class)); }
    @Test void mergesFiftySkusWithoutTouchingInputsOrCombiningTheirPrices() {
        var in=input(50);var before=in.deepCopy();var out=service().prepare(in);
        assertEquals(before,in);assertEquals(50,out.path("items").size());assertEquals("SKU49",out.at("/sheet/rows/49/sku").asText());assertEquals(50,out.at("/sheet/rows/49/number").asInt());
        assertEquals(5,out.at("/sheet/rows/49/prices/0").asInt());
    }
    @Test void rejectsTamperedMergedPriceAndSkuAndQuantityLabel() {
        var price=input(2);((ArrayNode)price.at("/sheet/rows/1/prices")).set(0,IntNode.valueOf(1));assertThrows(AppException.class,()->service().prepare(price));
        var sku=input(2);((ObjectNode)sku.at("/items/1/sheet/rows/0")).put("sku","SKU0");assertThrows(AppException.class,()->service().prepare(sku));
        var quantity=input(2);((ArrayNode)quantity.at("/items/1/sheet/quantityLabels")).set(0,StringNode.valueOf("100 pcs"));assertThrows(AppException.class,()->service().prepare(quantity));
    }
    @Test void rejectsDuplicateMissingOversizeAndOtherModes() {
        assertThrows(AppException.class,()->service().prepare(input(0)));assertThrows(AppException.class,()->service().prepare(input(51)));
        var duplicate=input(2);((ObjectNode)duplicate.at("/items/1/input")).put("primarySku","SKU0");assertThrows(AppException.class,()->service().prepare(duplicate));
        for(var mode:java.util.List.of("bundle","fob","shipping-only","freight-trial")){var in=input(1);((ObjectNode)in.at("/items/0/input")).put("quoteMode",mode);assertThrows(AppException.class,()->service().prepare(in));}
    }
    @Test void checksEverySkuWithExistingPriceReadinessAndLogisticsGuardsAndPropagatesStaleFailure() {
        var validator=mock(QuotationSubmissionValidator.class);var readiness=mock(QuotationReadinessService.class);var logistics=mock(LogisticsQuotationGuard.class);
        var service=new BatchSingleQuotation(validator,readiness,logistics);service.prepare(input(3));
        verify(validator,times(3)).validate(any());verify(validator,times(3)).validateQuotePricing(any());verify(readiness,times(3)).assertCanCreate(any());verify(logistics,times(3)).validate(any());
        doThrow(AppException.conflict("资料已更新")).when(readiness).assertCanCreate(any());assertThrows(AppException.class,()->service.prepare(input(3)));
    }
}

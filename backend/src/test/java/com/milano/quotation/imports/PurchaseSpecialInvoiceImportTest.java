package com.milano.quotation.imports;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class PurchaseSpecialInvoiceImportTest {
    final PurchaseImportRowMapper mapper=new PurchaseImportRowMapper(JsonMapper.builder().build());
    final String[] headers={"SKU","克重(g)","起订量(件)","基准采购单价(CNY/件)","含票价(CNY/件)","票点","票类型","含专票价(CNY/件)","专票点","专票类型","类别","货源链接1","优化后价格"};
    @Test void importsIndependentGroupsAndIgnoresErroneousOptimizationColumn() {
        var schema=PurchaseWorkbookSchema.identify(headers,"产品优化登记表");
        var row=mapper.map("登记表",2,new String[]{"INV-001","100","1","100","103","3%","普票","113","13%","专票13","衣服","https://example.com","999"},schema);
        assertTrue(row.errors().isEmpty(),row.errors().toString());
        assertEquals(113,row.payload().path("specialTaxIncludedPriceCny").asInt());assertEquals(.13,row.payload().path("specialTaxPoint").asDouble());
        assertEquals(103,row.payload().path("taxIncludedPriceCny").asInt());assertEquals(.03,row.payload().path("taxPoint").asDouble());
        assertTrue(row.payload().path("tier2MinQty").isNull());assertEquals("衣服",row.payload().path("category").asText());
    }
    @Test void blankOptionalCellsStayBlankButMalformedValuesBlockImport() {
        var schema=PurchaseWorkbookSchema.identify(headers,"登记表");
        var values=new String[]{"INV-002","100","1","100","103","3%","普票","","","","衣服","",""};
        var blank=mapper.map("登记表",2,values,schema);assertTrue(blank.errors().isEmpty());assertTrue(blank.payload().path("specialTaxPoint").isNull());
        values[8]="0%";assertEquals(0,mapper.map("登记表",2,values,schema).payload().path("specialTaxPoint").asInt());
        for(var bad:new String[]{"-1","101%","not-number"}){values[8]=bad;assertFalse(mapper.map("登记表",2,values,schema).errors().isEmpty());}
    }
}

package com.milano.quotation.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PurchaseImportRowMapperTest {
    private final PurchaseImportRowMapper mapper=new PurchaseImportRowMapper(JsonMapper.builder().build());
    @Test void acceptsCompleteBusinessSkuAsReady(){var row=mapper.map(2,values("MLN-P-000001"));assertTrue(row.errors().isEmpty());assertTrue(row.payload().path("quoteReady").asBoolean());assertEquals("ready",row.payload().path("catalogState").asText());}
    @Test void acceptsMissingOptionalDimensionsAsReady(){var v=values("MLN-P-000002");v[9]="";v[10]="";v[11]="";var row=mapper.map(2,v);assertTrue(row.errors().isEmpty());assertTrue(row.payload().path("quoteReady").asBoolean());assertTrue(row.payload().path("lengthCm").isNull());}
    @Test void blocksReservedTestSku(){var row=mapper.map(2,values("TESTP260001"));assertFalse(row.errors().isEmpty());assertTrue(row.errors().getFirst().contains("测试SKU"));assertFalse(row.payload().path("quoteReady").asBoolean());}
    @Test void reportsRequiredInvalidAndOptionalWarnings(){
        var v=values("");v[1]="";v[4]="";v[5]="not-date";v[8]="-1";v[9]="";v[10]="bad";v[12]="1.5";v[14]="bad";v[18]="bad";v[21]="未知";v[24]="未知";
        var row=mapper.map(3,v);
        assertFalse(row.errors().isEmpty());assertTrue(row.warnings().size()>=6);assertEquals("模板待补全（不可报价）",row.payload().path("status").asText());assertTrue(row.payload().path("weightKg").isNull());
    }
    @Test void normalizesCurrencyBuildsTiersAndChoices(){
        var v=values(" abc-1 ");v[8]="1,000";v[13]="¥ 10.50";v[14]="100";v[15]="RMB 9";v[16]="200";v[17]="CNY 8";v[21]="是";v[22]="￥11";v[24]="待确认";
        var row=mapper.map(4,v);
        assertTrue(row.errors().isEmpty());assertEquals("ABC-1",row.sku());assertEquals(3,row.payload().path("priceTiers").size());assertEquals(0,row.payload().path("weightKg").asDouble()-1.0,0.0001);
    }
    @Test void rejectsIllegalAndAllReservedPrefixesAndHandlesShortInput(){
        assertFalse(mapper.map(2,new String[]{"bad sku"}).errors().isEmpty());
        assertFalse(mapper.map(2,null).errors().isEmpty());
        for(var sku:java.util.List.of("TEST1","DEMO_1","MOCK/1","AUTO-1"))assertTrue(PurchaseImportRowMapper.reserved(sku));
        assertFalse(PurchaseImportRowMapper.reserved("AUTOMATIC-1"));
    }
    @Test void mapsInternationalTaxPointMaterialStockAndFuzzyWeight(){
        var values=new String[34];java.util.Arrays.fill(values,"");
        values[1]="2026-08-27";values[2]="采购员";values[4]="SKU-INT-1";values[6]="1600g左右";values[9]="棉";
        values[13]="1";values[14]="10";values[24]="8%";values[25]="专票13";values[27]="定制款";
        var row=mapper.map("张汝玉",2,values,new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.INTERNATIONAL));
        assertTrue(row.errors().isEmpty());assertEquals(1600,row.payload().path("weightG").asInt());assertEquals("1600g左右",row.payload().path("weightOriginal").asText());
        assertEquals(0.08,row.payload().path("taxPoint").asDouble(),0.0001);assertEquals("专票",row.payload().path("invoiceType").asText());assertEquals("棉",row.payload().path("material").asText());assertEquals("定制款",row.payload().path("stockStatus").asText());assertTrue(row.payload().path("quoteReady").asBoolean());
    }
    private static String[] values(String sku){var v=new String[32];java.util.Arrays.fill(v,"");v[0]=sku;v[1]="运动内衣";v[4]="采购员";v[5]="2026-08-24";v[8]="350";v[9]="23.1";v[10]="23.1";v[11]="5.6";v[12]="1";v[13]="65.61";v[24]="有货";return v;}
}

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
        assertTrue(row.errors().isEmpty());assertEquals("system",row.payload().path("skuOrigin").asText());assertTrue(row.sku().startsWith("AUTO-"));assertTrue(row.warnings().size()>=6);assertEquals("模板待补全（不可报价）",row.payload().path("status").asText());assertTrue(row.payload().path("weightKg").isNull());
    }
    @Test void normalizesCurrencyBuildsTiersAndChoices(){
        var v=values(" abc-1 ");v[8]="1,000";v[13]="¥ 10.50";v[14]="100";v[15]="RMB 9";v[16]="200";v[17]="CNY 8";v[21]="是";v[22]="￥11";v[24]="待确认";
        var row=mapper.map(4,v);
        assertTrue(row.errors().isEmpty());assertEquals("ABC-1",row.sku());assertEquals(3,row.payload().path("priceTiers").size());assertEquals(0,row.payload().path("weightKg").asDouble()-1.0,0.0001);
    }
    @Test void rejectsIllegalAndAllReservedPrefixesAndHandlesShortInput(){
        assertFalse(mapper.map(2,new String[]{"bad sku"}).errors().isEmpty());
        assertTrue(mapper.map(2,null).errors().isEmpty());assertEquals("system",mapper.map(2,null).payload().path("skuOrigin").asText());
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
    @Test void sourceFingerprintIsStableForNoSkuRowsAcrossTasksAndImageChanges(){
        var values=values("");
        var schema=new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY);
        var first=mapper.map("FIRST",1,"采购",31,values,schema);
        values[2]="图片内容已过滤";
        var next=mapper.map("SECOND",1,"采购",31,values,schema);
        assertNotEquals(first.sku(),next.sku());
        assertEquals(first.sourceContentHash(),next.sourceContentHash());
        values[0]="MLN-REAL-31";
        assertNotEquals(first.sourceContentHash(),mapper.map("SECOND",1,"采购",31,values,schema).sourceContentHash());
    }
    @Test void mapsLegacy2026PriceFreightSourceAndBlocksMissingKeyInformation(){
        var headers=new String[]{"SKU","报价人","报价日期","备注","克重/g","颜色/sku","1件运费","报价","类别","含票价","票点","票类型","工厂信息","审核备注","货源"};
        var schema=PurchaseWorkbookSchema.identifyOrNull(headers,1);assertNotNull(schema);assertTrue(schema.legacy2026Layout());
        var values=new String[]{"OLD-260001","采购员","2026.1.3","旧记录","70","黑色","1.7","6","","6.18","3%","普票","工厂A","","https://example.test"};
        var row=mapper.mapLegacy2026("TASK",1,"陈晨",2,values,schema);
        assertTrue(row.errors().isEmpty());assertTrue(row.payload().path("quoteReady").asBoolean());
        assertEquals("legacy_2026",row.payload().path("dataSource").asText());assertEquals("2026旧数据",row.payload().path("sourceLabel").asText());
        assertEquals(6,row.payload().path("sourceQuotedPriceCny").asDouble());assertEquals(6.18,row.payload().path("purchasePriceCny").asDouble());assertEquals("tax_included",row.payload().path("purchasePriceBasis").asText());
        assertEquals(1,row.payload().path("minOrderQty").asInt());assertEquals(1.7,row.payload().path("singleFreightCny").asDouble());assertTrue(row.payload().path("freight10Cny").isNull());assertEquals(0.03,row.payload().path("taxPoint").asDouble(),0.0001);
        assertEquals("黑色",row.payload().path("color").asText());assertEquals("https://example.test",row.payload().path("sourceLink1").asText());assertEquals(0,row.payload().path("quotationBlockingReasons").size());

        values[4]="";values[6]="";values[9]="";
        var pending=mapper.mapLegacy2026("TASK",1,"陈晨",3,values,schema);
        assertFalse(pending.payload().path("quoteReady").asBoolean());assertEquals("quoted",pending.payload().path("purchasePriceBasis").asText());
        assertEquals(java.util.List.of("克重","1件运费"),java.util.stream.StreamSupport.stream(pending.payload().path("quotationBlockingReasons").spliterator(),false).map(node->node.asText()).toList());
        assertEquals("关键信息待补全（不可报价）",pending.payload().path("status").asText());

        values[4]="520备注更新510";values[6]="包邮";
        var ambiguousWeight=mapper.mapLegacy2026("TASK",1,"陈晨",4,values,schema);
        assertTrue(ambiguousWeight.payload().path("weightG").isNull());assertEquals(0,ambiguousWeight.payload().path("singleFreightCny").asDouble());
        assertEquals("是",ambiguousWeight.payload().path("freeShipping").asText());assertTrue(ambiguousWeight.warnings().stream().anyMatch(text->text.contains("多个不同数值")));

        values[4]="5g左右";
        var fuzzyWeight=mapper.mapLegacy2026("TASK",1,"陈晨",5,values,schema);
        assertEquals(5,fuzzyWeight.payload().path("weightG").asInt());
    }
    private static String[] values(String sku){var v=new String[32];java.util.Arrays.fill(v,"");v[0]=sku;v[1]="运动内衣";v[4]="采购员";v[5]="2026-08-24";v[8]="350";v[9]="23.1";v[10]="23.1";v[11]="5.6";v[12]="1";v[13]="65.61";v[24]="有货";return v;}
}

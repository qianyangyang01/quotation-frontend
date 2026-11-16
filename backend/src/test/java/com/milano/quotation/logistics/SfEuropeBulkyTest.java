package com.milano.quotation.logistics;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class SfEuropeBulkyTest {
    final ObjectMapper m=new ObjectMapper();
    ObjectNode parse(String note,String country,String name)throws Exception{
        try(var b=new XSSFWorkbook()){
            var s=b.createSheet(name);
            LogisticsSourceParserTest.row(s,0,"","序号","Country","国家名称","Code","参考时效（工作日）","运费/kg","挂号费/票","计费重量限制（kg）");
            LogisticsSourceParserTest.row(s,1,"",1,"Germany","德国","DE","12-20天",90,42,"0-3KG");
            LogisticsSourceParserTest.row(s,2,"",1,"Germany","德国","DE","12-20天",90,62,"3.001-30KG");
            LogisticsSourceParserTest.row(s,5,"",country,note,"","","长*宽*高（cm）/18000");
            return (ObjectNode)new LogisticsSourceParser(m,new LogisticsWorkbookService(m)).parse(LogisticsSourceParserTest.bytes(b),"顺丰.xlsx").path("channels").get(0);
        }
    }
    static final String NOTE="本渠道仅支持一票一件；运费精确至克，体积重按/18000体积重计算，与实重相比较，取大值计算运费。";
    @Test void appliesGramStepBeforeThreeKgTariffBoundary()throws Exception{
        var c=parse(NOTE,"欧盟25国",SfEuropeBulkyRules.NAME);
        assertTrue(c.path("pricingReady").asBoolean(),c.toString());
        var engine=new LogisticsBillingEngine(m);
        assertEquals(312,engine.calculate(c.path("rows"),m.createObjectNode().put("country","DE").put("weightKg",3)).path("total").asDouble());
        var over=engine.calculate(c.path("rows"),m.createObjectNode().put("country","DE").put("weightKg",3.0001));
        assertEquals(3.001,over.path("chargeWeightKg").asDouble());assertEquals(332.09,over.path("total").asDouble());
        assertEquals(18000,c.path("rows").get(0).path("volumeDivisor").asInt());
        assertThrows(com.milano.quotation.common.AppException.class,()->engine.calculate(c.path("rows"),m.createObjectNode().put("country","DE").put("weightKg",30.0001)));
    }
    @Test void missingChangedOrWrongCountryEvidenceBlocks()throws Exception{
        for(String note:new String[]{"",NOTE.replace("精确至克","按0.5kg进位"),NOTE.replace("18000","6000")})assertFalse(parse(note,"欧盟25国",SfEuropeBulkyRules.NAME).path("pricingReady").asBoolean());
        assertFalse(parse(NOTE,"英国",SfEuropeBulkyRules.NAME).path("pricingReady").asBoolean());
    }
    @Test void unrelatedChannelRemainsUnchanged()throws Exception{
        var c=parse(NOTE,"欧盟25国","服装专线");
        assertEquals(0,c.path("rows").get(0).path("billingStepKg").asDouble());
    }
}

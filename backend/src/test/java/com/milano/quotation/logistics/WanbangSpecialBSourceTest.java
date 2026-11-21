package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WanbangSpecialBSourceTest {
    final WanbangPreferredSourceTest helper=new WanbangPreferredSourceTest(){
        @Override JsonNode source() throws Exception {
            try(var in=getClass().getResourceAsStream("/wanbang-specialb-20261121.json")){return mapper.readTree(in);}
        }
    };
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)helper.mapper.readTree(Files.readString(Path.of("../scripts/logistics/wanbang-specialb-company-channel.json")));
        CompanyChannelService.validate(config.path("entries"));
        return new CompanyChannelScope(config.put("enabled",true).put("revision",1));
    }
    void reconcile(JsonNode result,int increase,String code) throws Exception {
        assertEquals(1,result.path("channels").size(),result.toPrettyString());var channel=result.path("channels").get(0);
        assertEquals("万邦优选特敏B",channel.path("channelName").asText());assertEquals("保健品",channel.path("logisticsAttribute").asText());
        assertEquals(0,channel.path("errors").asInt(),channel.toPrettyString());assertTrue(channel.path("quoteReady").asBoolean());assertEquals(33,channel.path("rows").size());
        for(var expected:helper.source().path("channels").get(0).path("rows")) {
            var row=channel.path("rows").valueStream().filter(r->r.path("countryCode").equals(expected.path("countryCode"))&&r.path("weightFromKg").asDouble()==expected.path("weightFromKg").asDouble()).findFirst().orElseThrow();
            for(String field:List.of("weightToKg","weightFromInclusive","weightToInclusive","registrationFee","etaMinDays","etaMaxDays","minChargeWeightKg","currency","pricingModel")) {
                var e=expected.path(field);var a=row.path(field);
                if(e.isNumber())assertEquals(0,e.decimalValue().compareTo(a.decimalValue()),field+" "+expected);
                else assertEquals(e,a,field+" "+expected);
            }
            assertEquals(expected.path("pricePerKg").asDouble()+increase,row.path("pricePerKg").asDouble());
            assertEquals(code,row.path("sourceProductCode").asText());assertEquals(0,row.path("billingStepKg").asDouble());
            var from=expected.path("weightFromKg").decimalValue();var to=expected.path("weightToKg").decimalValue();
            for(var w:List.of(from.signum()==0?new BigDecimal(".001"):from,from.add(to).divide(BigDecimal.TWO),to)) {
                var total=w.multiply(expected.path("pricePerKg").decimalValue().add(BigDecimal.valueOf(increase))).add(expected.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP);
                var actual=helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("weightKg",w));
                assertEquals(0,total.compareTo(actual.path("total").decimalValue()));
            }
            if(from.signum()>0)assertThrows(AppException.class,()->helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("weightKg",from.subtract(new BigDecimal(".0005")))));
        }
        assertThrows(AppException.class,()->helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country","US").put("weightKg",1)));
        assertThrows(AppException.class,()->helper.engine.calculate(channel.path("rows"),helper.mapper.createObjectNode().put("country","IT").put("weightKg",5.001)));
    }
    @Test void sourcePricesAndTierBoundariesSurviveMovedHeadersReorderedColumnsAndFutureUpdates() throws Exception {
        reconcile(helper.parser.parse(helper.fixture(false,"",0),"万邦20261123.xlsx",directory()),0,"EUSLHC-P");
        reconcile(helper.parser.parse(helper.fixture(true,"",3),"万邦20271201.xlsx",directory()),3,"EUSLHC-P");
        reconcile(helper.parser.parse(helper.fixture(true,"code",3),"万邦产品代码更新.xlsx",directory()),3,"OTHER-P");
    }
    @Test void missingPriceFeeAndWeightNeverProducePartialPublishableUpdates() throws Exception {
        for(var defect:List.of("weight-column","weight-row","fee-column","fee-cell","price-cell")) {
            var result=helper.parser.parse(helper.fixture(true,defect,0),"万邦更新.xlsx",directory());
            assertEquals(1,result.path("channels").size(),defect+result.toPrettyString());
            assertFalse(result.path("channels").get(0).path("quoteReady").asBoolean(),defect);
        }
        assertTrue(LogisticsProductIdentity.codeConflict("万邦","万邦优选专线敏感","EUSLHC-P"));
        assertTrue(LogisticsProductIdentity.codeConflict("万邦","万邦优选特敏B","ECSL-P"));
        assertFalse(directory().match("万邦","欧洲优选特敏B CLSM","EUSLHC-P-CLSM").accepted());
    }
    @Test @EnabledIfSystemProperty(named="wanbang.specialb.source",matches=".+")
    void fullOriginalWorkbookKeepsClsmAndFooterFeesOutOfBasePrices() throws Exception {
        var path=Path.of(System.getProperty("wanbang.specialb.source"));
        var result=helper.parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        reconcile(result,0,"EUSLHC-P");
        assertEquals(Set.of(6,7,8,9,10,11,12,13,14,15,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43),
                new HashSet<>(result.path("channels").get(0).path("rows").valueStream().map(r->r.path("sourceRow").asInt()).toList()));
        Files.createDirectories(Path.of("target/wanbang-specialb"));Files.writeString(Path.of("target/wanbang-specialb/parsed.json"),result.toPrettyString());
    }
}

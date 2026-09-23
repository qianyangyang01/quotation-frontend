package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsKuwaitCosmeticsPricingTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LogisticsBillingEngine engine = new LogisticsBillingEngine(mapper);
    ObjectNode row() {
        return mapper.createObjectNode().put("countryCode","KW").put("areaName","科威特")
            .put("sourceSheet","云途全球化妆品类专线挂号").put("billingStepKg",.1).put("pricingModel","per-kg")
            .put("weightFromKg",0).put("weightToKg",5).put("minChargeWeightKg",.1).put("pricePerKg",74).put("registrationFee",75);
    }
    ObjectNode input(double weight) { return mapper.createObjectNode().put("country","KW").put("weightKg",weight); }

    @Test void reconcilesEveryWholeGramUpToFiveKilogramsUsingIntegerCents() {
        var rows=mapper.createArrayNode().add(row());
        for (int grams=1;grams<=5000;grams++) {
            int units=(grams+99)/100;
            var result=engine.calculate(rows,input(grams/1000.0));
            assertEquals(units/10.0,result.path("chargeWeightKg").asDouble(),grams+"g weight");
            assertEquals((units*740+7500)/100.0,result.path("total").asDouble(),grams+"g fee");
        }
    }

    @Test void sourceImportPreservesTheConfirmedStepAndDoesNotWarnThatItIsUnsupported() throws Exception {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = book.createSheet("云途全球化妆品类专线挂号");
            LogisticsSourceParserTest.row(sheet,0,"国家/地区","参考时效","重量(KG)","进位制(KG)","最低计费重(KG)","运费(RMB/KG)","挂号费(RMB/票)");
            LogisticsSourceParserTest.row(sheet,1,"科威特","8-12工作日","0<W≤5",.1,.1,74,75);
            var parsed=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper))
                    .parse(LogisticsSourceParserTest.bytes(book),"云途价格.xlsx").path("channels").get(0).path("rows").get(0);
            assertTrue(LogisticsStepPricing.supported(parsed),parsed.toPrettyString());
            assertEquals(.1,parsed.path("billingStepKg").asDouble());
            assertFalse(parsed.path("reviewWarning").asText().contains("普通计费进位规则需要适配"));
            assertEquals(89.8,engine.calculate(mapper.createArrayNode().add(parsed),input(.12)).path("total").asDouble());
        }
    }

    @Test void roundsAfterMinimumAndBeforeTierSelection() {
        var rows = mapper.createArrayNode().add(row());
        for (var sample : new double[][]{{.05,.1,82.4},{.1,.1,82.4},{.100001,.2,89.8},{.12,.2,89.8},
                {.2,.2,89.8},{.21,.3,97.2},{.3,.3,97.2},{.301,.4,104.6},{4.999,5,445},{5,5,445}}) {
            var result = engine.calculate(rows,input(sample[0]));
            assertEquals(sample[0],result.path("actualWeightKg").asDouble());
            assertEquals(sample[1],result.path("chargeWeightKg").asDouble());
            assertEquals(sample[2],result.path("total").asDouble());
        }
        for (var weight : new double[]{0,-1,5.000001}) assertThrows(AppException.class,()->engine.calculate(rows,input(weight)));
        var tiers=mapper.createArrayNode().add(row().put("weightToKg",.15)).add(row().put("weightFromKg",.15).put("pricePerKg",100));
        assertEquals(95,engine.calculate(tiers,input(.12)).path("total").asDouble());
        assertThrows(AppException.class,()->engine.calculate(mapper.createArrayNode().add(row().put("weightToKg",.15)),input(.12)));
        assertThrows(AppException.class,()->engine.calculate(mapper.createArrayNode().add(row()).add(row().put("billingStepKg",0)),input(.12)));
    }

    @Test void supportsOtherExplicitStepsAndPreservesUnconfiguredRows() {
        for (var other : java.util.List.of(row().put("countryCode","QA"),row().put("sourceSheet","云途全球服装专线挂号"))) {
            var result=engine.calculate(mapper.createArrayNode().add(other),input(.12).put("country",other.path("countryCode").asText()));
            assertEquals(.2,result.path("chargeWeightKg").asDouble());
        }
        assertEquals(.12,engine.calculate(mapper.createArrayNode().add(row().put("billingStepKg",0)),input(.12)).path("chargeWeightKg").asDouble());
        assertThrows(AppException.class,()->engine.calculate(mapper.createArrayNode().add(row().put("billingStepKg","0.1")),input(.12)));
    }
}

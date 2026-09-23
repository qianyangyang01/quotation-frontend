package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsStepPricingTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static JsonNode fixtures() throws Exception {
        try (var input = LogisticsStepPricingTest.class.getResourceAsStream("/logistics-rounding-20260923.json")) {
            return MAPPER.readTree(input.readAllBytes());
        }
    }
    @Test void reconcilesReviewedProductionTariffsAndUnaffectedChannelsAgainstIndependentDecimalOracle() throws Exception {
        var engine = new LogisticsBillingEngine(MAPPER);
        for (var fixture : fixtures()) {
            var source = fixture.path("source");
            var row = LogisticsRoundingNotes.apply(source);
            String context = fixture.path("channel").asText()+"/"+source.path("countryCode").asText()+"/"+source.path("sourceRow").asInt();
            assertEquals(fixture.path("expectedBands").isNull() ? null : fixture.path("expectedBands"), row.get("billingStepBands"), context);
            var rows = MAPPER.createArrayNode().add(row);
            for (var sample : fixture.path("samples")) {
                var input = MAPPER.createObjectNode().put("country",source.path("countryCode").asText()).put("zoneName",source.path("zoneName").asText().split("[/／、,，;；|]")[0]).set("weightKg",sample.path("weight"));
                if (sample.path("fee").isNull()) assertThrows(AppException.class, ()->engine.calculate(rows,input), context+sample);
                else {
                    var result=engine.calculate(rows,input);
                    assertEquals(sample.path("charge").decimalValue().stripTrailingZeros(),result.path("chargeWeightKg").decimalValue().stripTrailingZeros(),context+sample);
                    assertEquals(sample.path("fee").decimalValue().stripTrailingZeros(),result.path("total").decimalValue().stripTrailingZeros(),context+sample);
                }
            }
        }
    }
    @Test void rejectsMalformedStepsBandsAndConflictingConfigurations() {
        var row=new LogisticsKuwaitCosmeticsPricingTest().row();
        for (var invalid : java.util.List.of(row.deepCopy().put("pricingModel","per-kg-1g"),row.deepCopy().put("pricingModel","per-piece-500g"),row.deepCopy().put("billingStepKg",-1),row.deepCopy().put("billingStepKg","0.1"),
                row.deepCopy().set("billingStepBands",MAPPER.createArrayNode()),
                row.deepCopy().put("billingStepKg",0).set("billingStepBands",MAPPER.readTree("[{\"aboveKg\":1,\"stepKg\":0.1}]")),
                row.deepCopy().put("billingStepKg",0).set("billingStepBands",MAPPER.readTree("[{\"aboveKg\":0,\"stepKg\":0.1},{\"aboveKg\":0,\"stepKg\":1}]"))))
            assertFalse(LogisticsStepPricing.supported(invalid),invalid.toString());
    }
    @Test void noteScopeDoesNotOverwriteExplicitStepsAndRequiresProvenance() {
        var row = (ObjectNode) MAPPER.readTree("{\"pricingModel\":\"per-kg\",\"sourceFile\":\"通邮20260921.xlsx\",\"sourceSheet\":\"纯电池线路\",\"countryCode\":\"KR\",\"notes\":\"按照1KG进位，即1.001KG按照2KG计费\"}");
        assertEquals(1,LogisticsRoundingNotes.apply(row).path("billingStepBands").get(0).path("stepKg").asInt());
        for (var patch : java.util.List.of(row.deepCopy().put("countryCode","US"),row.deepCopy().put("sourceFile","其他.xlsx"),row.deepCopy().put("notes",""),row.deepCopy().put("billingStepKg",.1)))
            assertFalse(LogisticsRoundingNotes.apply(patch).has("billingStepBands"));
    }

    @Test void standardExcelRoundTripPreservesCountrySpecificBandsAndPrices() throws Exception {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.simple.JdbcClient.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        var dataset=java.util.UUID.randomUUID();
        var source=MAPPER.createObjectNode().put("provider","通邮").put("channel","通邮挂号特货").put("attribute","普货")
                .put("id",java.util.UUID.randomUUID().toString()).put("status","published");
        var row=new LogisticsKuwaitCosmeticsPricingTest().row().put("billingStepKg",0).put("countryCode","CL").put("areaName","智利")
                .put("sourceFile","通邮价格.xlsx").put("sourceSheet","通邮挂号特货").put("notes","2KG以上按照0.5进位");
        source.putObject("version").putArray("rows").add(row);
        org.mockito.Mockito.when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()).param("dataset",dataset).param("version",null)
            .query(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ObjectNode>>any()).list()).thenReturn(java.util.List.of(source));
        var bytes=new LogisticsExportService(jdbc,MAPPER).prices(dataset,null,"","","",null,false);
        var parsed=new LogisticsSourceParser(MAPPER,new LogisticsWorkbookService(MAPPER)).parse(bytes,"导出价格.xlsx");
        var restored=parsed.path("channels").get(0).path("rows").get(0);
        assertEquals(LogisticsRoundingNotes.apply(row).path("billingStepBands").toString(),restored.path("billingStepBands").toString());
        assertEquals(row.path("pricePerKg").decimalValue(),restored.path("pricePerKg").decimalValue());
        assertEquals(row.path("registrationFee").decimalValue(),restored.path("registrationFee").decimalValue());
        assertEquals(260,new LogisticsBillingEngine(MAPPER).calculate(MAPPER.createArrayNode().add(restored),
                MAPPER.createObjectNode().put("country","CL").put("weightKg",2.001)).path("total").asDouble());
        assertThrows(IllegalArgumentException.class,()->LogisticsStepPricing.importBands("错误规则"));
        assertThrows(IllegalArgumentException.class,()->LogisticsStepPricing.importBands("1以上:0.1"));
    }
}

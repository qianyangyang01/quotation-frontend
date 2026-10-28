package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsPriceHistoryCalculatorTest {
    final JsonMapper mapper=JsonMapper.builder().build();
    final LogisticsPriceHistoryCalculator calculator=new LogisticsPriceHistoryCalculator(mapper,new LogisticsBillingEngine(mapper));
    @Test void calculatesOtherPublishedDestinationsWithoutRestrictingToPriorityCountries(){
        var rows=mapper.createArrayNode();
        for(var country:java.util.List.of("NZ","JP","ZA","US"))rows.addObject().put("countryCode",country).put("weightFromKg",0).put("weightToKg",30).put("pricePerKg",20).put("registrationFee",5).put("pricingModel","per-kg");
        var result=calculator.calculate(rows);
        for(var country:java.util.List.of("NZ","JP","ZA","US")){
            assertEquals(15,result.path(country+"|0.5").path("全国统一").path("price").asDouble());
            assertEquals(25,result.path(country+"|1").path("全国统一").path("price").asDouble());
            assertEquals(45,result.path(country+"|2").path("全国统一").path("price").asDouble());
        }
        assertFalse(result.has("GB|1"));
    }
    @Test void computesThreeWeightsAndSeparateRegionsWithoutChangingSource(){
        var rows=mapper.createArrayNode();
        rows.addObject().put("countryCode","AU").put("zoneName","1区").put("weightFromKg",0).put("weightToKg",30).put("pricePerKg",20).put("registrationFee",5).put("minChargeWeightKg",1).put("pricingModel","per-kg");
        rows.addObject().put("countryCode","AU").put("zoneName","2区").put("weightFromKg",0).put("weightToKg",30).put("pricePerKg",40).put("registrationFee",5).put("pricingModel","per-kg");
        var original=rows.toString();var result=calculator.calculate(rows);
        assertEquals(25,result.path("AU|0.5").path("1区").path("price").asDouble());
        assertEquals(45,result.path("AU|1").path("2区").path("price").asDouble());
        assertEquals(45,result.path("AU|2").path("1区").path("price").asDouble());
        assertFalse(result.has("DE|1"));assertEquals(original,rows.toString());
    }
    @Test void gapsAndUnsupportedPricesStayUnavailable(){
        var rows=mapper.createArrayNode().add(mapper.createObjectNode().put("countryCode","DE").put("weightFromKg",1).put("weightToKg",2).put("pricePerKg",20).put("pricingModel","per-kg"));
        var result=calculator.calculate(rows);assertTrue(result.path("DE|0.5").path("全国统一").path("price").isNull());
        assertEquals(40,result.path("DE|2").path("全国统一").path("price").asDouble());
    }
    @Test void optionalReadOnlyRealSnapshotParityFixture() throws Exception {
        var file=System.getProperty("priceHistoryFixture","");if(file.isEmpty())return;
        var snapshot=(tools.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(Path.of(file)));
        long start=System.nanoTime();int count=0;
        for(var version:snapshot.path("versions")){
            var v=(tools.jackson.databind.node.ObjectNode)version;
            v.set("preparedPrices",calculator.calculate(v.path("rows")));v.putArray("rows");count++;
        }
        Files.writeString(Path.of(System.getProperty("priceHistoryPrepared")),snapshot.toString());
        System.out.println("PREPARED_VERSIONS="+count+" TOTAL_MS="+(System.nanoTime()-start)/1000000);
    }
}

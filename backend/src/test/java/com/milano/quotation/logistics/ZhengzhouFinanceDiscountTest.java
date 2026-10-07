package com.milano.quotation.logistics;

import com.milano.quotation.finance.ChannelFreightDiscounts;
import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ZhengzhouFinanceDiscountTest {
    static final String CHANNEL_ID = "00000000-0000-0000-0000-000000000601";
    static final ObjectMapper mapper = new ObjectMapper();
    static ObjectNode settings() {
        var settings = mapper.createObjectNode();
        var rule = settings.putArray("rules").addObject().put("channelId",CHANNEL_ID).put("basis","base-excluding-linehaul").put("enabled",true).put("defaultFactor",new BigDecimal("1.05"));
        var countries = rule.putObject("countries");
        for (var pair : "AU=.97 NZ=.99 JP=.94 DE=.97 PL=.97 BE=.97 GB=.97 ES=.97 ID=.96 SA=1.01 KZ=.96 FI=.96 FR=.96 IE=.96 NL=.96 VN=.96 BR=.96 IT=.92 HU=.96 SG=1 KR=.92 TR=.96 TH=.96 CH=.96 SE=.96 PT=.96 NO=.96 MX=.96 MY=.96".split(" ")) {
            var parts=pair.split("="); countries.put(parts[0],new BigDecimal(parts[1]));
        }
        return settings;
    }
    static ObjectNode original() throws Exception {
        var fixture = new YanwenZhengzhouEpacketTest();
        return (ObjectNode)fixture.parser.parse(fixture.source(),"中邮郑州线下E邮宝.xlsx",fixture.scope()).path("channels").get(0);
    }
    @Test void allCountriesAndWeightsUseFinanceWithoutChangingSourcePrices() throws Exception {
        var original=original();var snapshot=original.toString();var channel=original.deepCopy();
        var settings=settings();var adjusted=channel.putArray("rows");
        for(var row:original.path("rows"))adjusted.add(ChannelFreightDiscounts.apply(row,CHANNEL_ID,settings));
        assertEquals(58,adjusted.size());assertEquals(snapshot,original.toString());
        var evidence=mapper.createObjectNode();evidence.set("channel",channel);evidence.set("settings",settings);var cases=evidence.putArray("cases");
        var engine=new LogisticsBillingEngine(mapper);
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(new YanwenZhengzhouEpacketTest().source()))) {
            for(var row:adjusted) {
                var raw=book.getSheetAt(0).getRow(row.path("sourceRow").asInt()-1);
                var kg=new BigDecimal(raw.getCell(3).toString());var fee=new BigDecimal(raw.getCell(4).toString());var linehaul=new BigDecimal(raw.getCell(7).toString());
                var country=row.path("countryCode").asText();var factors=settings.path("rules").get(0);
                var factor=factors.path("countries").has(country)?factors.path("countries").path(country).decimalValue():new BigDecimal("1.05");
                var upper=row.path("weightToKg").decimalValue();
                for(var weight:List.of(new BigDecimal(".0005"),new BigDecimal(".001"),new BigDecimal(".1"),new BigDecimal(".292"),BigDecimal.ONE,upper)) {
                    var charge=weight.max(new BigDecimal(".001"));
                    var expected=charge.multiply(kg).add(fee).multiply(factor).add(charge.multiply(linehaul)).setScale(2,RoundingMode.HALF_UP);
                    var input=mapper.createObjectNode().put("country",country).put("weightKg",weight).put("zoneName",row.path("zoneName").asText());
                    assertEquals(0,expected.compareTo(engine.calculate(adjusted,input).path("total").decimalValue()),input.toString());
                    cases.addObject().set("input",input).put("expected",expected);
                }
                assertThrows(AppException.class,()->engine.calculate(adjusted,mapper.createObjectNode().put("country",country).put("weightKg",upper.add(new BigDecimal(".001"))).put("zoneName",row.path("zoneName").asText())));
            }
        }
        assertThrows(AppException.class,()->engine.calculate(adjusted,mapper.createObjectNode().put("country","US").put("weightKg",1)));
        Files.writeString(Path.of("target/zhengzhou-discount-cases.json"),evidence.toPrettyString());
    }
    @Test void disablingChangingAndChannelIsolationNeverMutateOriginal() throws Exception {
        var source=original().path("rows").get(0);var settings=settings();
        assertEquals(source,ChannelFreightDiscounts.apply(source,"602::燕文::different",settings));
        assertEquals(source,ChannelFreightDiscounts.apply(source,CHANNEL_ID,mapper.createObjectNode()));
        ((ObjectNode)settings.path("rules").get(0)).put("enabled",false);
        assertEquals(source,ChannelFreightDiscounts.apply(source,CHANNEL_ID,settings));
        ((ObjectNode)settings.path("rules").get(0)).put("enabled",true);
        ((ObjectNode)settings.path("rules").get(0).path("countries")).put("AU",.9);
        assertEquals(58.8,ChannelFreightDiscounts.apply(source,CHANNEL_ID,settings).path("pricePerKg").asDouble());
        assertEquals(65,source.path("pricePerKg").asDouble());
    }
    @Test void missingSourceOrAlreadyDiscountedPricesCannotBeDiscountedByGuessing() throws Exception {
        var source=(ObjectNode)original().path("rows").get(0);
        for(var field:List.of("sourcePricePerKg","sourceLinehaulPerKg","registrationFee")) {
            var bad=source.deepCopy();bad.remove(field);
            assertFalse(ChannelFreightDiscounts.apply(bad,CHANNEL_ID,settings()).path("quoteReady").asBoolean());
        }
        var adjusted=ChannelFreightDiscounts.apply(source,CHANNEL_ID,settings());
        assertFalse(ChannelFreightDiscounts.apply(adjusted,CHANNEL_ID,settings()).path("quoteReady").asBoolean());
        var changed=source.deepCopy().put("pricePerKg",50);
        assertFalse(ChannelFreightDiscounts.apply(changed,CHANNEL_ID,settings()).path("quoteReady").asBoolean());
        for(var value:List.of("0","-1","2.001","null","\".97\"")) {
            var invalid=settings();((ObjectNode)invalid.path("rules").get(0)).set("defaultFactor",mapper.readTree(value));
            assertThrows(AppException.class,()->ChannelFreightDiscounts.validate(invalid));
        }
    }
    @Test void generalRulesSupportMultipleChannelsAndFullFreightWithoutLinehaulMetadata() {
        var settings=settings();var second=((tools.jackson.databind.node.ArrayNode)settings.path("rules")).addObject()
            .put("channelId","00000000-0000-0000-0000-000000000602").put("basis","full-freight").put("enabled",true).put("defaultFactor",.9);
        second.putObject("countries").put("GB",.8);
        var source=mapper.createObjectNode().put("countryCode","gb").put("pricingModel","per-kg-1g").put("pricePerKg",100).put("registrationFee",20);
        var result=ChannelFreightDiscounts.apply(source,second.path("channelId").asText(),settings);
        assertEquals(80,result.path("pricePerKg").asDouble());assertEquals(16,result.path("registrationFee").asDouble());
        source.put("countryCode","FR");assertEquals(90,ChannelFreightDiscounts.apply(source,second.path("channelId").asText(),settings).path("pricePerKg").asDouble());
        source.put("pricingModel","per-piece-500g").put("intervalPrice",50).put("pricePerKg",0);
        assertEquals(45,ChannelFreightDiscounts.apply(source,second.path("channelId").asText(),settings).path("intervalPrice").asDouble());
        second.put("channelName","改名渠道");assertEquals(45,ChannelFreightDiscounts.apply(source,second.path("channelId").asText(),settings).path("intervalPrice").asDouble());
        assertEquals(source,ChannelFreightDiscounts.apply(source,"00000000-0000-0000-0000-000000000603",settings));
        second.put("basis","base-excluding-linehaul");assertFalse(ChannelFreightDiscounts.apply(source,second.path("channelId").asText(),settings).path("quoteReady").asBoolean());
    }
    @Test void duplicateChannelsAndUnknownBasisAreRejected() {
        var settings=settings();var rules=(tools.jackson.databind.node.ArrayNode)settings.path("rules");rules.add(rules.get(0).deepCopy());
        assertThrows(AppException.class,()->ChannelFreightDiscounts.validate(settings));rules.remove(1);
        ((ObjectNode)rules.get(0)).put("basis","unknown");assertThrows(AppException.class,()->ChannelFreightDiscounts.validate(settings));
    }
    @Test void readOnlyProductionTariffMatchesIndependentFormulaAtEveryGram() throws Exception {
        var path=System.getenv("ZHENGZHOU_PRODUCTION_SOURCE");
        org.junit.jupiter.api.Assumptions.assumeTrue(path!=null&&!path.isBlank(),"Optional release gate uses a read-only production tariff export");
        var export=mapper.readTree(Files.readString(Path.of(path)).replace("\uFEFF",""));
        assertTrue(export.path("ready").asBoolean());
        var channel=(ObjectNode)export.path("channel").deepCopy();var original=channel.path("rows");var before=original.toString();
        assertEquals(58,original.size());
        var settings=settings();((ObjectNode)settings.path("rules").get(0)).put("channelId",export.path("channelId").asText());
        var adjusted=channel.putArray("rows");
        for(var row:original) {
            assertEquals("per-kg",row.path("pricingModel").asText());
            assertEquals(0,row.path("sourcePricePerKg").decimalValue().add(row.path("sourceLinehaulPerKg").decimalValue()).compareTo(row.path("pricePerKg").decimalValue()));
            adjusted.add(ChannelFreightDiscounts.apply(row,export.path("channelId").asText(),settings));
        }
        var evidence=mapper.createObjectNode();evidence.set("channel",channel);evidence.set("settings",settings);
        evidence.set("sourceVersionId",export.path("versionId"));evidence.set("sourceFingerprint",export.path("rowsFingerprint"));
        var cases=evidence.putArray("cases");var engine=new LogisticsBillingEngine(mapper);
        for(var row:original) {
            var country=row.path("countryCode").asText();var zone=row.path("zoneName").asText();
            var factors=settings.path("rules").get(0);var factor=factors.path("countries").has(country)?factors.path("countries").path(country).decimalValue():factors.path("defaultFactor").decimalValue();
            var weights=new java.util.LinkedHashSet<BigDecimal>();weights.add(new BigDecimal(".0005"));
            var upper=row.path("weightToKg").decimalValue();
            for(int gram=1;gram<=upper.multiply(new BigDecimal("1000")).intValueExact();gram++) weights.add(BigDecimal.valueOf(gram,3));
            weights.add(upper.subtract(new BigDecimal(".0001")));
            for(var weight:weights) {
                var charge=weight.max(new BigDecimal(".001"));
                var expected=charge.multiply(row.path("sourcePricePerKg").decimalValue()).add(row.path("registrationFee").decimalValue()).multiply(factor)
                    .add(charge.multiply(row.path("sourceLinehaulPerKg").decimalValue())).setScale(2,RoundingMode.HALF_UP);
                var input=mapper.createObjectNode().put("country",country).put("zoneName",zone).put("weightKg",weight);
                assertEquals(0,expected.compareTo(engine.calculate(adjusted,input).path("total").decimalValue()),input.toString());
                cases.addArray().add(country).add(zone).add(weight).add(expected);
            }
            var overflow=mapper.createObjectNode().put("country",country).put("zoneName",zone).put("weightKg",upper.add(new BigDecimal(".0001")));
            assertThrows(AppException.class,()->engine.calculate(adjusted,overflow));
        }
        assertEquals(before,original.toString());
        Files.writeString(Path.of("target/zhengzhou-production-cases.json"),evidence.toString());
    }
}

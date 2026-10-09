package com.milano.quotation.fob;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FobSourceParserTest {
    @Test void preservesAllFiveTiersFromPastedPf2600049AndEstimatedFreight() {
        var p = FobSourceParser.parse("1", "单件：23.99单价\n100起单价19.5\n300单价19.5\n500单价19\n1000单价19", "一件运费：4\t10件运费：11\t100件预拍运费：74");
        assertEquals(List.of(1, 100, 300, 500, 1000), p.priceTiers().stream().map(FobSourceParser.Tier::minQty).toList());
        assertEquals(List.of("23.99", "19.5", "19.5", "19", "19"), p.priceTiers().stream().map(t -> t.unitPriceCny().toPlainString()).toList());
        assertEquals(new BigDecimal("0.74"), p.freight().unitFreightCny()); assertTrue(p.freight().estimated());
    }
    @Test void supportsFullWidthAndRangeSeparatorsWithoutGuessingAmbiguousConditions() {
        for (String range : List.of("１～９９", "1至99", "1到99", "1—99", "1–99", "1 - 99")) {
            var p = FobSourceParser.parse("１", range + "件单价２３．９９；１００件以上单价１９．５", "１００件总运费：７４");
            assertEquals(2, p.priceTiers().size()); assertEquals(99, p.priceTiers().getFirst().maxQty());
            assertEquals(new BigDecimal("23.99"), p.priceTiers().getFirst().unitPriceCny());
        }
        for (String value : List.of("单价10;100件9美元", "单价10;100件9-10", "单价10;100件8;100件9", "单价10;100件-5", "单价10;100件8另加包装费1", "单价10;100件8元每箱20件"))
            assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", value, "包邮"), value);
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "1".repeat(10001), "包邮"));
        String tooMany = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> i + "件单价1").collect(java.util.stream.Collectors.joining(";"));
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", tooMany, "包邮"));
        assertTrue(FobSourceParser.parse("1", "12", "试拍1件3/10件5/100件74").freight().estimated());
    }
    @Test void crossChecksShippingInPriceTextInsteadOfSilentlyDroppingConditions() {
        var p = FobSourceParser.parse("3", "单价：7.62；100件以上6.5包邮；500件以上6包邮；", "预拍一件运费：3.5；50元以上包邮");
        assertEquals(3, p.priceTiers().size()); assertEquals(BigDecimal.ZERO, p.freight().unitFreightCny());
        assertTrue(p.notices().stream().anyMatch(n -> n.contains("已与运费原文核对")));
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "单价8;100件6包邮", "100件30"));
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "单价8;500件6包邮", "100件0"));
    }
    @Test void preservesFiveTiersIncludingEqualPricesAndHonorsMoq() {
        var p = FobSourceParser.parse("2", "单价16\n100起单价13.8元\n300单价13元\n500单价13元\n1000单价12.8元", "包邮");
        assertEquals(5, p.priceTiers().size()); assertEquals(2, p.priceTiers().getFirst().minQty());
        assertEquals(99, p.priceTiers().getFirst().maxQty()); assertEquals(499, p.priceTiers().get(2).maxQty());
        assertEquals(p.priceTiers().get(2).unitPriceCny(), p.priceTiers().get(3).unitPriceCny());
        assertNull(p.priceTiers().getLast().maxQty()); assertEquals(BigDecimal.ZERO, p.freight().unitFreightCny());
    }
    @Test void matchesFourNestedBreakpointsWithoutTreatingAllGreaterThanAsOverlap() {
        var p = FobSourceParser.parse("1", "单价:17.9\n30-99件:17.5\n≥100件:16.9\n≥10000件:14.5", "一件运费:3.8;10件运费7;100件预拍运费32.6");
        assertEquals(4, p.priceTiers().size()); assertEquals(9999, p.priceTiers().get(2).maxQty());
        assertEquals(new BigDecimal("0.326"), p.freight().unitFreightCny()); assertTrue(p.freight().estimated());
    }
    @Test void shippingUses100ThenSmallestLargerQuantityNeverLowestUnitPrice() {
        var p = FobSourceParser.parse("1", "7.2", "1件3;10件5;200件30;400件20");
        assertEquals(200, p.freight().quantity()); assertEquals(new BigDecimal("0.15"), p.freight().unitFreightCny());
        assertEquals(100, FobSourceParser.parse("1", "7.2", "100件40;200件30").freight().quantity());
        assertEquals(new BigDecimal("0.15"), FobSourceParser.parse("10000", "7.9", "10000盒发物流预计1500左右").freight().unitFreightCny());
    }
    @Test void onePieceFreeShippingDoesNotOverrideHundredPieceFreight() {
        assertEquals(new BigDecimal("0.3"), FobSourceParser.parse("2", "单件16.5;100起单价12.8", "一件运费:包邮;10件运费3;100件预拍运费30").freight().unitFreightCny());
        assertEquals(new BigDecimal("0.3"), FobSourceParser.parse("2", "16.5", "1件包邮;10件3;100件30").freight().unitFreightCny());
        assertEquals(BigDecimal.ZERO, FobSourceParser.parse("1", "16.5", "1件3;100件包邮").freight().unitFreightCny());
    }
    @Test void handlesReverseLinesUnitsAndQuantityMultiplesWithoutRoundingPrices() {
        var p = FobSourceParser.parse("", "2.50\n3双起批\n\n2.40\n200-499双\n\n2.30\n≥500双", "试拍1双运费2.6/3双以上包邮");
        assertEquals(3, p.minOrderQty()); assertEquals("双", p.priceTiers().getFirst().unit());
        assertEquals(3, p.priceTiers().size()); assertEquals(BigDecimal.ZERO, p.freight().unitFreightCny());
        assertEquals(10, FobSourceParser.parse("10的倍数下单；", "6", "100件104").orderMultiple());
        assertEquals(new BigDecimal("0.023"), FobSourceParser.parse("500", "500-0.07；1000-0.04；2000-0.023", "包邮").priceTiers().getLast().unitPriceCny());
    }
    @Test void acceptsPunctuationAndRejectsUnconsumedFreightConditions() {
        assertEquals(2, FobSourceParser.parse("1", "单价15;≥100件，单价14", "100件32.4").priceTiers().size());
        assertEquals(3, FobSourceParser.parse("1", "单价38，30-99双37,100以上36", "100件32.4").priceTiers().size());
        assertEquals("双", FobSourceParser.parse("1", "单价38，30-99双37,100以上36", "100件32.4").priceTiers().getFirst().unit());
        assertEquals(BigDecimal.ZERO, FobSourceParser.parse("1", "12", "1件3;50元以上包邮").freight().unitFreightCny());
        assertEquals(200, FobSourceParser.parse("1", "12", "200件以上包邮").freight().quantity());
        for (var freight : new String[]{"100件30;偏远地区加收10", "100件-30", "100件30-40", "10030", "10件以上包邮;100件30"})
            assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "12", freight), freight);
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "16,17", "包邮"));
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "16 17", "包邮"));
    }
    @Test void rejectsMissingFreightOverlapsExtraFeesAndAmbiguousPrices() {
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "20", "1件6;10件10"));
        for (var value : new String[]{"1套起批18;150-299套17.5;150-299套17", "单价12.6;60-1000件12.1;1000以上9.5", "单价27;批发价24", "单价0.5;版费470", "500件28.1000件25;3000件22"})
            assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", value, "包邮"), value);
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("1", "500-1000:1.4", "100件3.8"));
        assertThrows(IllegalArgumentException.class, () -> FobSourceParser.parse("", "29.5;500以上27.5", "100件102"));
    }
}

package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsPriceSearchTest {
    @ParameterizedTest
    @ValueSource(strings={"云途化妆品","化妆品云途","云途 化妆品","化妆品 云途","云途\u3000化妆品", "云途\u00a0化妆品", "云途\t化妆品\n挂号", "化妆品", ""})
    void combinesProviderAndChannelFragments(String query) {
        assertTrue(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms(query),"云途","云途全球化妆品类专线挂号"));
    }
    @ParameterizedTest
    @ValueSource(strings={"云途 化妆品 服装","云途 不存在","其他物流化妆品","%","_",".*","' OR 1=1 --"})
    void requiresEveryLiteralTermOnTheSameChannel(String query) {
        assertFalse(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms(query),"云途","云途全球化妆品类专线挂号"));
    }
    @Test void handlesCaseWhitespaceAndAbsentNames() {
        assertEquals(List.of("yun","cosmetics"),LogisticsPriceSearch.terms("  YUN\tcosmetics\u3000yun  "));
        assertTrue(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms("YUNcosmetics"),"Yun","Global COSMETICS"));
        assertTrue(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms("%"),null,"special%channel"));
        assertFalse(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms("云途化妆品"),null,"全球化妆品"));
        assertFalse(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms("化妆品"),"云途",null));
        assertTrue(LogisticsPriceSearch.matches(LogisticsPriceSearch.terms(null),null,null));
    }
}

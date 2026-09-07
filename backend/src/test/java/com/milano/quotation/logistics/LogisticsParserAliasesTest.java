package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LogisticsParserAliasesTest {
    final LogisticsParserAliases aliases=new LogisticsParserAliases();

    @Test void normalizesCommonAliasesAndKeepsSurchargesSeparate(){
        assertTrue(aliases.matches("花海","registrationFee"," 操作费（RMB／票） "));
        assertTrue(aliases.matches("燕文","registrationFee","处理费(元/件)"));
        assertTrue(aliases.matches("递四方","registrationFee","每票费"));
        assertFalse(aliases.matches("花海","registrationFee","燃油附加费"));
        assertFalse(aliases.matches("花海","registrationFee","超尺寸费"));
        assertTrue(aliases.matches("顺丰","settlementRate","SF折后"));
        assertTrue(aliases.classify("花海","收费标准").isEmpty());
    }

    @Test void providerExactRuleWinsBeforeCommonExpression(){
        var yaml="""
                common:
                  registrationFee:
                    contains: ["操作费"]
                providers:
                  特殊物流:
                    settlementRate:
                      exact: ["操作费"]
                """;
        var configured=new LogisticsParserAliases(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("settlementRate",configured.classify("特殊物流","操作费").orElseThrow());
        assertEquals("registrationFee",configured.classify("普通物流","操作费/票").orElseThrow());
    }

    @Test void rejectsConflictingExactAliasesAndInvalidRegex(){
        var conflict="""
                common:
                  pricePerKg:
                    exact: ["价格"]
                  registrationFee:
                    exact: ["价格"]
                """;
        assertThrows(IllegalStateException.class,()->new LogisticsParserAliases(new ByteArrayResource(conflict.getBytes(StandardCharsets.UTF_8))));
        var invalid="""
                common:
                  pricePerKg:
                    regex: ["["]
                """;
        assertThrows(IllegalStateException.class,()->new LogisticsParserAliases(new ByteArrayResource(invalid.getBytes(StandardCharsets.UTF_8))));
    }
}

package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Offline validation of the exact reviewed finance payload and frontend snapshots. */
class SunyouFinancePreparationTest {
    @Test @EnabledIfSystemProperty(named="sunyou.financeDir", matches=".+")
    void acceptsSourceBasedSettingsAndRejectsNonServiceDestinations() throws Exception {
        var root = Path.of(System.getProperty("sunyou.financeDir"));
        var mapper = new ObjectMapper();
        var prepared = mapper.readTree(Files.readString(root.resolve("finance-prepared.json")));
        for (var entry : prepared.properties()) FinanceSettingValidation.validate(entry.getKey(), entry.getValue());
        var live = mapper.readTree(Files.readString(root.resolve("live-before.json")).replace("\uFEFF", ""));
        var exchange = live.path("settings").path("exchange-rate").path("payload");
        var evidence = mapper.readTree(Files.readString(root.resolve("tax-calculation-evidence.json")));
        assertTrue(evidence.path("passed").asBoolean());
        var preparedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("finance-prepared.json"))));
        assertEquals(preparedHash, evidence.path("preparedSha256").asText());
        int validated = 0, rejected = 0;
        for (var check : evidence.path("checks")) {
            var result = check.path("result");
            var option = mapper.createObjectNode().put("country", check.path("country").asText()).put("channelKey", check.path("key").asText())
                .put("taxConfigured", result.path("configured").asBoolean()).put("taxIncluded", result.path("included").asBoolean())
                .put("taxFeeMode", result.path("feeMode").asText()).set("countryFixedTaxUsd", result.path("fixedFeeUsd"));
            option.set("tax1Usd", result.path("taxUsd"));
            if (result.has("calculation")) option.putObject("taxCalculations").set("1", result.path("calculation"));
            if (!result.path("configured").asBoolean()) {
                assertThrows(AppException.class, () -> ChannelTaxRules.validateQuote(prepared.path("tax-settings"), option, exchange, 1));
                rejected++;
            } else if (result.has("calculation")) {
                assertTrue(ChannelTaxRules.validateQuote(prepared.path("tax-settings"), option, exchange, 1), check.toString());
                validated++;
            } else assertFalse(ChannelTaxRules.validateQuote(prepared.path("tax-settings"), option, exchange, 1));
        }
        assertTrue(validated > 300); assertTrue(rejected >= 48);
        Files.writeString(root.resolve("native-tax-evidence.json"), mapper.createObjectNode().put("passed", true)
            .put("preparedSha256", preparedHash).put("validatedSnapshots", validated).put("rejectedUnavailable", rejected).toPrettyString());
    }
}

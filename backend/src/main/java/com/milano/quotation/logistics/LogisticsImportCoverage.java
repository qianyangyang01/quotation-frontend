package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Coverage is about which existing prices were checked, not whether a file was uploaded successfully. */
final class LogisticsImportCoverage {
    private LogisticsImportCoverage() {}

    static ObjectNode calculate(JsonNode payload, List<ObjectNode> currentChannels) {
        var providers = new TreeSet<String>();
        var included = new HashSet<String>();
        for (var result : payload.path("results")) {
            providers.add(CompanyChannelScope.normalize(result.path("providerName").asText()));
            if (!result.path("channelId").asText().isBlank()) included.add(result.path("channelId").asText());
        }
        // A selected provider remains in scope even if all of its sheets failed to parse.
        if (!payload.path("selectedProviderName").asText().isBlank())
            providers.add(CompanyChannelScope.normalize(payload.path("selectedProviderName").asText()));
        for (var report : payload.path("fileReports")) {
            providers.add(CompanyChannelScope.normalize(report.path("providerName").asText()));
            for (var sheet : report.path("sheets")) for (var match : sheet.path("channelMatches"))
                providers.add(CompanyChannelScope.normalize(match.path("providerName").asText()));
        }
        providers.remove("");
        var report = JsonNodeFactory.instance.objectNode();
        var missing = report.putArray("missingChannels");
        int existing = 0;
        for (var channel : currentChannels.stream().sorted(Comparator.comparing(c -> c.path("channelId").asText())).toList()) {
            if (!providers.contains(CompanyChannelScope.normalize(channel.path("providerName").asText()))) continue;
            existing++;
            if (!included.contains(channel.path("channelId").asText())) missing.add(channel.deepCopy());
        }
        report.put("existingChannels", existing).put("coveredChannels", existing - missing.size())
                .put("missingCount", missing.size()).put("partial", !missing.isEmpty());
        // Version IDs make an acknowledgment stale when an omitted channel changes concurrently.
        report.put("token", LogisticsDatasetService.hash(missing.toString()));
        return report;
    }

    static void requireAcknowledgment(JsonNode coverage, JsonNode input) {
        if (!coverage.path("partial").asBoolean()) return;
        if (!input.path("partialUpdateConfirmed").asBoolean())
            throw AppException.unprocessable("本批未覆盖同物流商的 " + coverage.path("missingCount").asInt() + " 个现行渠道。请核对未更新清单并明确确认局部更新，其他渠道仍沿用旧价");
        if (!coverage.path("token").asText().equals(input.path("coverageToken").asText()))
            throw AppException.conflict("未更新渠道清单已变化，请刷新批次并重新核对局部更新范围");
    }
}

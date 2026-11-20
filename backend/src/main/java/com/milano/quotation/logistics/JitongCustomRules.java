package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import java.util.Set;

/** The four separately registered tax-inclusive custom products; source codes may change. */
final class JitongCustomRules {
    private static final Set<String> NAMES=Set.of("极通环球-定制特惠普货","极通环球-定制特惠特货",
            "极通环球-定制特惠敏感","极通环球-特惠定制敏感","极通环球-定制特惠纯电");
    private JitongCustomRules() {}
    static boolean channel(JsonNode channel) {
        return channel.path("providerName").asText().equals("极通环球")
                &&NAMES.stream().anyMatch(name->CompanyChannelScope.normalize(name)
                    .equals(CompanyChannelScope.normalize(channel.path("channelName").asText())));
    }
}

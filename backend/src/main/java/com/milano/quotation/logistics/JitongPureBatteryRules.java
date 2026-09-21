package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;

/** The requested custom battery product, distinct from the optional postal-label product. */
final class JitongPureBatteryRules {
    static final String NAME="极通环球专线 - 定制纯电";
    static final String CODE="JT-HQ-MDCD";
    private JitongPureBatteryRules() {}
    static boolean named(String value){return CompanyChannelScope.normalize(value).equals(CompanyChannelScope.normalize(NAME));}
    static boolean channel(JsonNode channel){return channel.path("providerName").asText().equals("极通环球")&&named(channel.path("channelName").asText());}
    static String code(String raw){return java.util.regex.Pattern.compile("(?<![A-Z0-9-])"+CODE+"(?![A-Z0-9-])").matcher(raw).find()?CODE:"";}
}

package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;

/** The requested custom battery product, distinct from the optional postal-label product. */
final class JitongPureBatteryRules {
    static final String NAME="极通环球专线 - 定制纯电";
    static final String CODE="JT-HQ-MDCD";
    private JitongPureBatteryRules() {}
    static boolean named(String value){return CompanyChannelScope.normalize(value).equals(CompanyChannelScope.normalize(NAME));}
    static boolean channel(JsonNode channel){return channel.path("providerName").asText().equals("极通环球")&&named(channel.path("channelName").asText());}
    static String code(String raw){
        var labelled=java.util.regex.Pattern.compile("(?:产品代码|产品编号|渠道代码)\\s*[:：]\\s*([A-Za-z0-9_-]+)").matcher(raw);
        if(labelled.find())return labelled.group(1);
        for(var cell:raw.split("\\R"))if(cell.trim().matches("[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+"))return cell.trim();
        var match=java.util.regex.Pattern.compile("(?<![A-Z0-9-])JT-[A-Z0-9]+(?:-[A-Z0-9]+)+(?![A-Z0-9-])",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
        return match.find()?match.group():raw.trim().matches("[A-Za-z0-9_-]+")?raw.trim():"";
    }
}

package com.milano.quotation.logistics;

import java.time.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Only an explicit source label establishes an effective date; filenames do not. */
final class LogisticsEffectiveDate {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final Pattern LABELLED=Pattern.compile("(?i)(?:生效日期|生效时间|生效日|effective\\s*(?:date|from))\\s*[:：]?\\s*((?:19|20)\\d{2})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})(?:\\s*日)?(?:[ T]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    private LogisticsEffectiveDate() {}

    static String parse(String text) {
        var match=LABELLED.matcher(text);
        if(!match.find())return "";
        try {
            var date=LocalDate.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)));
            var time=match.group(4)==null?LocalTime.MIDNIGHT:LocalTime.of(Integer.parseInt(match.group(4)),Integer.parseInt(match.group(5)),match.group(6)==null?0:Integer.parseInt(match.group(6)));
            var result=date.atTime(time).atZone(BUSINESS_ZONE).toOffsetDateTime().toString();
            if(match.find())throw new IllegalArgumentException("同一处存在多个生效日期，请明确本表生效时间");
            return result;
        }catch(DateTimeException e){throw new IllegalArgumentException("原表生效日期或时间无效，请核对原表",e);}
    }

    /** Source dates are reference metadata, never a publication gate. */
    static void project(tools.jackson.databind.node.ObjectNode payload) {
        payload.put("effectiveDateWarning", "").put("effectiveDateRequiresConfirmation", false);
        int removed=0;
        for(var value:payload.path("issues")) {
            if(isDateIssue(value)&&value.path("level").asText().equals("error")) {
                var issue=(tools.jackson.databind.node.ObjectNode)value;
                issue.put("level","warning").put("originalLevel","error");
                removed++;
            }
        }
        if(removed==0)return;
        int remaining=0;
        for(var issue:payload.path("issues"))if(issue.path("level").asText().equals("error"))remaining++;
        int errors=Math.max(remaining,Math.max(0,payload.path("errors").asInt()-removed));
        payload.put("errors",errors);
        // Compact workspace/batch projections do not contain price rows.
        boolean ready=errors==0&&payload.path("blockingReasons").isEmpty()
                &&payload.path("pendingRows").asInt()==0
                &&payload.path("templateStatus").asText("known").equals("known")
                &&(payload.path("rowCount").asInt()>0||!payload.path("rows").isEmpty());
        payload.put("pricingReady",ready).put("quoteReady",ready);
    }

    static boolean isDateIssue(JsonNode issue) {
        return java.util.Set.of("生效时间","生效日期").contains(issue.path("field").asText());
    }
}

package com.milano.quotation.logistics;

import java.time.*;
import java.time.format.DateTimeFormatter;
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

    static void requireConfirmation(JsonNode payload,boolean confirmed,Instant now) {
        var reason=blockingReason(payload,now);
        if(!reason.isBlank()&&(!confirmed||reason.contains("无效")))throw com.milano.quotation.common.AppException.unprocessable(reason);
    }

    static void project(tools.jackson.databind.node.ObjectNode payload) {
        var warning=blockingReason(payload,Instant.now());
        payload.put("effectiveDateWarning",warning);
        payload.put("effectiveDateRequiresConfirmation",!warning.isBlank());
    }

    static String blockingReason(JsonNode payload,Instant now) {
        var dates=tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for(var row:payload.path("rows"))if(row.has("effectiveAt"))dates.add(row.get("effectiveAt"));
        return blockingDates(dates,now);
    }

    static String blockingDates(JsonNode dates,Instant now) {
        Instant latest=null;
        for(var date:dates) {
            try {
                var instant=OffsetDateTime.parse(date.asText()).toInstant();
                if(latest==null||instant.isAfter(latest))latest=instant;
            }catch(DateTimeException e){return "原表生效时间无效，请重新导入并核对";}
        }
        if(latest==null||!latest.isAfter(now))return "";
        return "原表生效时间为 "+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(BUSINESS_ZONE).format(latest)
                +"（北京时间），尚未到生效时间，请到期后发布，或明确确认物流商同意提前执行";
    }
}

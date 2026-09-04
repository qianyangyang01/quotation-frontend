package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Derives route-level ETA readiness and review flags without changing billing identity. */
final class LogisticsReadiness {
    private static final Set<String> SUPPORTED_MODELS=Set.of("per-kg","first-next");
    private static final Set<String> GENERATED_ETA_CODES=Set.of("ETA_MISSING","ETA_PARTIAL","ETA_CONFLICT");
    private static final Set<String> GENERATED_REASONS=Set.of("缺少时效","时效范围不完整","同一路线存在冲突时效","区间价计费方式暂不支持","未知计费方式");

    private LogisticsReadiness() {}

    static void apply(ObjectNode target,ObjectMapper mapper) {apply(target);}

    static void apply(ObjectNode target) {
        int priorErrors=target.path("errors").asInt();
        var rows=target.withArray("rows");
        var issues=target.withArray("issues");
        var preserved=tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for(var issue:issues)if(!GENERATED_ETA_CODES.contains(issue.path("code").asText()))preserved.add(issue.deepCopy());
        issues.removeAll();issues.addAll(preserved);

        var routes=new LinkedHashMap<String,List<ObjectNode>>();var routeFamilies=new LinkedHashMap<String,List<ObjectNode>>();
        for(var value:rows) {
            var row=(ObjectNode)value;
            row.put("sourceProductCode",row.path("sourceProductCode").asText(row.path("sourceCode").asText()));
            if(!row.has("sourceOriginRegion"))row.put("sourceOriginRegion",row.path("originRegion").asText());
            row.put("blockingReason",withoutGenerated(row.path("blockingReason").asText(row.path("pendingReason").asText())));
            row.put("pendingReason",row.path("blockingReason").asText());
            row.put("reviewWarning",withoutGenerated(row.path("reviewWarning").asText()));
            if(row.path("sourceProductCode").asText().isBlank())warn(row,"原表未提供产品代码，使用系统渠道编码");
            if(row.path("originRegion").asText().isBlank()&&row.path("sourceOriginRegion").asText().isBlank())warn(row,"原表未标注报价区域");
            var model=row.path("pricingModel").asText();
            if(model.isBlank()){model=row.path("firstWeightPrice").asDouble()>0?"first-next":row.path("intervalPrice").asDouble()>0?"interval":row.path("pricePerKg").asDouble()>0?"per-kg":"unknown";row.put("pricingModel",model);}
            if(row.path("sourceFeeLabel").asText().isBlank()&&row.path("registrationFee").asDouble()>0)row.put("sourceFeeLabel","挂号费");
            if(row.path("etaMinDays").asInt()>0&&row.path("etaMaxDays").asInt()>=row.path("etaMinDays").asInt()&&row.path("etaSource").asText().isBlank())row.put("etaSource","source-row");
            if(model.equals("interval"))block(row,"区间价计费方式暂不支持");
            else if(!SUPPORTED_MODELS.contains(model))block(row,"未知计费方式");
            else if(model.equals("per-kg")&&row.path("pricePerKg").asDouble()<=0)block(row,"公斤价计费结构不完整");
            else if(model.equals("first-next")&&(row.path("firstWeightKg").asDouble()<=0||row.path("firstWeightPrice").asDouble()<=0
                    ||(row.path("weightToKg").asDouble()>row.path("firstWeightKg").asDouble()&&(row.path("nextWeightKg").asDouble()<=0||row.path("nextWeightPrice").asDouble()<=0))))block(row,"首续重计费结构不完整");
            if(row.path("surcharge").asDouble()>0)block(row,"附加费需要明确计费适用规则");
            if(row.path("fuelSurchargeRate").asDouble()>0)block(row,"燃油附加费率尚未接入自动计费");
            if(row.path("linehaulPerKg").asDouble()>0)block(row,"干线费需要明确计费叠加规则");
            if(row.path("billingStepKg").asDouble()>0&&!model.equals("first-next"))block(row,"普通计费进位规则需要适配");
            if(!row.path("currency").asText("CNY").equals("CNY"))block(row,"非人民币计价需要币种适配");
            var routeKey=routeKey(row);row.put("routeKey",routeKey);
            routes.computeIfAbsent(routeKey,ignored->new ArrayList<>()).add(row);
            var family=String.join("|",normalize(row.path("countryCode").asText()),normalize(row.path("areaName").asText()),normalize(row.path("zoneName").asText()));
            routeFamilies.computeIfAbsent(family,ignored->new ArrayList<>()).add(row);
        }

        for(var family:routeFamilies.values()) {
            var origins=new LinkedHashSet<String>();for(var row:family)if(!row.path("originRegion").asText().isBlank())origins.add(normalize(row.path("originRegion").asText()));
            if(origins.size()>1)for(var row:family)block(row,"同一路线存在多套报价区域价格");
        }

        var missing=target.putArray("missingEtaRoutes");
        for(var entry:routes.entrySet()) {
            var values=new LinkedHashSet<Eta>();var partial=new ArrayList<ObjectNode>();
            for(var row:entry.getValue()) {
                int min=row.path("etaMinDays").asInt(),max=row.path("etaMaxDays").asInt();
                if(min>0&&max>=min)values.add(new Eta(min,max));
                else if(min>0||max>0)partial.add(row);
            }
            if(!partial.isEmpty()) {
                for(var row:entry.getValue())block(row,"时效范围不完整");
                for(var row:partial)issue(issues,row,"ETA_PARTIAL","时效","时效最早和最晚天数必须同时填写");
                missing.add(routeView(entry.getKey(),entry.getValue().getFirst(),"partial"));
            } else if(values.size()>1) {
                for(var row:entry.getValue())block(row,"同一路线存在冲突时效");
                issue(issues,entry.getValue().getFirst(),"ETA_CONFLICT","时效","同一渠道、国家、分区和报价区域存在不同的时效范围");
                missing.add(routeView(entry.getKey(),entry.getValue().getFirst(),"conflict"));
            } else if(values.isEmpty()) {
                for(var row:entry.getValue())block(row,"缺少时效");
                missing.add(routeView(entry.getKey(),entry.getValue().getFirst(),"missing"));
            } else {
                var eta=values.iterator().next();
                for(var row:entry.getValue())if(row.path("etaMinDays").asInt()<=0||row.path("etaMaxDays").asInt()<=0) {
                    row.put("etaMinDays",eta.min).put("etaMaxDays",eta.max).put("etaSource","route-inherited");
                } else if(row.path("etaSource").asText().isBlank())row.put("etaSource","source-row");
            }
        }

        var blockers=new TreeSet<String>();var warnings=new TreeSet<String>();int blockedRows=0,warningRows=0;
        for(var value:rows) {
            var row=(ObjectNode)value;
            var rowBlockers=split(row.path("blockingReason").asText());blockers.addAll(rowBlockers);
            var rowWarnings=split(row.path("reviewWarning").asText());warnings.addAll(rowWarnings);
            if(!rowBlockers.isEmpty())blockedRows++;
            if(!rowWarnings.isEmpty())warningRows++;
        }
        var blockingReasons=target.putArray("blockingReasons");blockers.forEach(blockingReasons::add);
        var reviewWarnings=target.putArray("reviewWarnings");warnings.forEach(reviewWarnings::add);
        int errors=0;for(var issue:issues)if("error".equals(issue.path("level").asText()))errors++;
        errors=Math.max(errors,priorErrors);
        boolean etaReady=missing.isEmpty();
        boolean ready=errors==0&&etaReady&&blockers.isEmpty()&&!rows.isEmpty()&&"known".equals(target.path("templateStatus").asText("known"));
        for(var value:rows)((ObjectNode)value).put("quoteReady",ready&&((ObjectNode)value).path("blockingReason").asText().isBlank());
        target.put("errors",errors).put("warnings",warningRows).put("etaReady",etaReady).put("etaMissingCount",missing.size())
                .put("pendingRows",blockedRows).put("reviewWarningRows",warningRows).put("quoteReady",ready).put("pricingReady",ready);
    }

    static String routeKey(JsonNode row) {
        var identity=String.join("|",normalize(row.path("countryCode").asText()),normalize(row.path("areaName").asText()),
                normalize(row.path("zoneName").asText()),normalize(row.path("originRegion").asText()));
        return LogisticsDatasetService.hash(identity);
    }

    static void applyBatch(ObjectNode payload) {
        var missing=payload.putArray("missingEtaRoutes");var blockers=new TreeSet<String>();var warnings=new TreeSet<String>();
        for(var result:payload.path("results")) {
            for(var value:result.path("missingEtaRoutes")){var route=(ObjectNode)value.deepCopy();route.put("providerName",result.path("providerName").asText()).put("channelName",result.path("channelName").asText());missing.add(route);}
            for(var value:result.path("blockingReasons"))if(!value.asText().isBlank())blockers.add(value.asText());
            for(var value:result.path("reviewWarnings"))if(!value.asText().isBlank())warnings.add(value.asText());
        }
        var blockingReasons=payload.putArray("blockingReasons");blockers.forEach(blockingReasons::add);
        var reviewWarnings=payload.putArray("reviewWarnings");warnings.forEach(reviewWarnings::add);
        payload.put("etaReady",missing.isEmpty()).put("etaMissingCount",missing.size());
    }

    private static ObjectNode routeView(String key,ObjectNode row,String status) {
        return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("routeKey",key).put("status",status)
                .put("areaName",row.path("areaName").asText()).put("countryCode",row.path("countryCode").asText())
                .put("zoneName",row.path("zoneName").asText()).put("originRegion",row.path("originRegion").asText())
                .put("sourceOriginRegion",row.path("sourceOriginRegion").asText()).put("sourceProductCode",row.path("sourceProductCode").asText())
                .put("sourceSheet",row.path("sourceSheet").asText()).put("sourceRow",row.path("sourceRow").asInt());
    }

    private static void issue(ArrayNode issues,ObjectNode row,String code,String field,String message) {
        issues.addObject().put("row",row.path("sourceRow").asInt()).put("sourceSheet",row.path("sourceSheet").asText())
                .put("rowKey",row.path("rowKey").asText()).put("routeKey",row.path("routeKey").asText())
                .put("code",code).put("field",field).put("message",message).put("level","error");
    }

    static void block(ObjectNode row,String reason) {row.put("blockingReason",append(row.path("blockingReason").asText(),reason));row.put("pendingReason",row.path("blockingReason").asText());}
    static void warn(ObjectNode row,String reason) {row.put("reviewWarning",append(row.path("reviewWarning").asText(),reason));}
    private static String append(String prior,String value){var values=split(prior);values.add(value);return String.join("；",values);}
    private static LinkedHashSet<String> split(String value){var values=new LinkedHashSet<String>();for(var item:value.split("；"))if(!item.isBlank())values.add(item.trim());return values;}
    private static String withoutGenerated(String value){var values=split(value);values.removeAll(GENERATED_REASONS);return String.join("；",values);}
    private static String normalize(String value){return value.replaceAll("[\\s（）()]","").toLowerCase(Locale.ROOT);}
    private record Eta(int min,int max){}
}

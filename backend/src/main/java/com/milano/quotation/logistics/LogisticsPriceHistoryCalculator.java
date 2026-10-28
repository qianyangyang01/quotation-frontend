package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

/** A publication-time projection of the existing billing engine, not a second pricing formula. */
@Component
public class LogisticsPriceHistoryCalculator {
    public static final String VERSION=LogisticsBillingEngine.VERSION+":price-history-2";
    private final ObjectMapper mapper;
    private final LogisticsBillingEngine billing;
    public LogisticsPriceHistoryCalculator(ObjectMapper mapper,LogisticsBillingEngine billing){this.mapper=mapper;this.billing=billing;}
    public ObjectNode calculate(JsonNode rows){
        if(rows==null||!rows.isArray()||rows.size()>50000)throw new IllegalArgumentException("Invalid or oversized price display source");
        for(var row:rows)if(!row.isObject())throw new IllegalArgumentException("Invalid price display row");
        var result=mapper.createObjectNode();
        var grouped=new java.util.LinkedHashMap<String,tools.jackson.databind.node.ArrayNode>();
        for(var row:rows){
            var country=row.path("countryCode").asText();
            if(country.matches("[A-Z]{2}")&&!row.path("zoneExclude").asBoolean())grouped.computeIfAbsent(country,key->mapper.createArrayNode()).add(row);
        }
        int regionCount=0;
        for(var entry:grouped.entrySet()){
            var country=entry.getKey();var countryRows=entry.getValue();var regions=new LinkedHashSet<String>();
            for(var row:countryRows){
                var zone=row.path("zoneName").asText().trim();
                if(zone.isEmpty())regions.add("全国统一");else regions.addAll(LogisticsBillingEngine.splitZones(zone));
            }
            regionCount+=regions.size();
            if(regionCount>1000)throw new IllegalArgumentException("Too many display regions");
            for(var weight:List.of("0.5","1","2")){
                var prices=result.putObject(country+"|"+weight);
                for(var region:regions){
                    if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Display calculation cancelled");
                    var point=prices.putObject(region);point.putNull("price").putNull("rate").putNull("fee").putNull("chargeWeight").putNull("intervalPrice").put("model","").put("reason","");
                    try{
                        var input=mapper.createObjectNode().put("country",country).put("weightKg",new BigDecimal(weight)).put("zoneName",region);
                        var calculation=billing.calculate(countryRows,input);var row=countryRows.get(calculation.path("rowIndex").asInt());
                        point.set("price",calculation.path("total"));point.set("chargeWeight",calculation.path("chargeWeightKg"));
                        point.set("rate",row.path("pricePerKg"));point.put("fee",row.path("registrationFee").asDouble(0));
                        point.put("model",row.path("pricingModel").asText("per-kg"));point.set("intervalPrice",row.path("intervalPrice"));
                    }catch(AppException exception){point.put("reason",exception.getMessage());}
                }
            }
        }
        return result;
    }
}

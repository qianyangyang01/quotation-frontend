package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

/** Read-time metadata enrichment only: never removes an error or changes a price. */
final class LogisticsIssueLocations {
    private static final Pattern LEGACY_ROWS=Pattern.compile("原始行号[：:]\\s*\\[([0-9,，\\s]+)]");
    static void enrich(ObjectNode payload) {
        var sheets=new LinkedHashSet<String>();
        for(var row:payload.path("rows"))if(!row.path("sourceSheet").asText().isBlank())sheets.add(row.path("sourceSheet").asText());
        for(var item:payload.path("issues")) {
            if(!(item instanceof ObjectNode issue))continue;
            if(!issue.has("sourceRows")&&issue.path("row").asInt()<=0) {
                var matcher=LEGACY_ROWS.matcher(issue.path("message").asText());
                if(matcher.find()) {
                    var values=issue.putArray("sourceRows");
                    for(var n:matcher.group(1).split("[,，\\s]+"))if(!n.isBlank())try{int row=Integer.parseInt(n);if(row>0)values.add(row);}catch(NumberFormatException ignored){}
                }
            }
            var candidates=new ArrayList<JsonNode>();
            for(var row:payload.path("rows")) {
                boolean keyed=!issue.path("rowKey").asText().isBlank()&&issue.path("rowKey").asText().equals(row.path("rowKey").asText());
                boolean numbered=issue.path("row").asInt()>0&&issue.path("row").asInt()==row.path("sourceRow").asInt();
                if((issue.path("rowKey").asText().isBlank()?numbered:keyed)&&(issue.path("sourceSheet").asText().isBlank()||issue.path("sourceSheet").asText().equals(row.path("sourceSheet").asText())))candidates.add(row);
            }
            var candidateSheets=new LinkedHashSet<String>();
            candidates.forEach(row->{if(!row.path("sourceSheet").asText().isBlank())candidateSheets.add(row.path("sourceSheet").asText());});
            if(issue.path("sourceSheet").asText().isBlank()) {
                if(candidateSheets.size()==1)issue.put("sourceSheet",candidateSheets.iterator().next());
                else if(sheets.size()==1)issue.put("sourceSheet",sheets.iterator().next());
            }
            if(candidates.size()==1) {
                var row=candidates.getFirst();
                if(!issue.has("rawValues")&&row.has("rawValues"))issue.set("rawValues",row.path("rawValues").deepCopy());
            }
        }
    }
}

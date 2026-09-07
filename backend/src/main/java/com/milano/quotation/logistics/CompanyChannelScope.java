package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;

/** Immutable import-time directory. This class never reads or evaluates price cells. */
public final class CompanyChannelScope {
    public record Match(String status, String reason, JsonNode entry) {
        public boolean accepted() { return status.equals("matched"); }
    }
    private final JsonNode snapshot;
    private final boolean unrestricted;
    private final Map<String,List<JsonNode>> providers = new HashMap<>();
    public CompanyChannelScope(JsonNode snapshot) {
        this.snapshot=snapshot.deepCopy(); unrestricted=!snapshot.path("enabled").asBoolean();
        for(var entry:this.snapshot.path("entries")) {
            var names=new HashSet<String>(); names.add(normalize(entry.path("providerName").asText()));
            for(var alias:entry.path("providerAliases"))names.add(normalize(alias.asText()));
            for(var name:names)providers.computeIfAbsent(name,k->new ArrayList<>()).add(entry);
        }
    }
    public boolean unrestricted(){return unrestricted;}
    public JsonNode snapshot(){return snapshot.deepCopy();}
    public long revision(){return snapshot.path("revision").asLong();}
    public boolean containsProvider(String provider){return unrestricted||providers.containsKey(normalize(provider));}
    public String providerFromFilename(String filename) {
        var stem=normalize(filename).replace("4px","递四方");var hits=new TreeSet<String>();
        for(var item:providers.entrySet())if(!item.getKey().isBlank()&&stem.contains(item.getKey()))
            for(var entry:item.getValue())hits.add(entry.path("providerName").asText());
        return hits.size()==1?hits.first():"";
    }
    public Match match(String provider,String name,String code) {
        if(unrestricted)return new Match("matched","兼容导入",null);
        var entries=providers.getOrDefault(normalize(provider),List.of());
        if(entries.isEmpty())return new Match("filtered","物流商不在公司清单",null);
        var byName=new LinkedHashMap<String,JsonNode>();var byCode=new LinkedHashMap<String,JsonNode>();
        for(var entry:entries) {
            if(matches(name,entry.path("channelName"),entry.path("aliases")))byName.put(entry.path("id").asText(),entry);
            if(!code.isBlank()&&matches(code,null,entry.path("productCodes")))byCode.put(entry.path("id").asText(),entry);
        }
        if(byName.size()>1||byCode.size()>1)return new Match("ambiguous","同一物流商下匹配多个渠道",null);
        if(!byName.isEmpty()&&!byCode.isEmpty()&&!byName.keySet().equals(byCode.keySet()))
            return new Match("ambiguous","产品代码与渠道名称指向不同渠道",null);
        var hits=byCode.isEmpty()?byName:byCode;
        if(hits.isEmpty())return new Match("filtered","渠道未登记或未登记该名称别名",null);
        var entry=hits.values().iterator().next();
        if(!entry.path("enabled").asBoolean(true))return new Match("filtered","公司渠道已停用",entry);
        return new Match("matched",byCode.isEmpty()?"渠道名称或登记别名":"原产品代码",entry);
    }
    private static boolean matches(String input,JsonNode name,JsonNode aliases) {
        var n=normalize(input);if(n.isBlank())return false;
        if(name!=null&&n.equals(normalize(name.asText())))return true;
        for(var alias:aliases)if(n.equals(normalize(alias.asText())))return true;
        return false;
    }
    public static String normalize(String value) {
        var n=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).replaceAll("[\\s\\u00a0]+","").toLowerCase(Locale.ROOT);
        return n.equals("4px")?"递四方":n;
    }
    public static void identify(ObjectNode target, Match match) {
        if(match.entry()==null)return;
        var entry=match.entry();target.put("companyChannelId",entry.path("id").asText());
        target.put("providerName",entry.path("providerName").asText()).put("channelName",entry.path("channelName").asText());
        target.put("logisticsAttribute",entry.path("logisticsAttribute").asText("普货"));
    }
}

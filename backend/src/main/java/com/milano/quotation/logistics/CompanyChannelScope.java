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
        return match(provider,List.of(name),List.of(code),false);
    }
    /** Rongding's A/C rows share prices, but each registered product must retain its own identity. */
    public Match matchSourceCell(String provider,String name,String code) {
        if(unrestricted||!normalize(provider).equals("容鼎")||code.isBlank())return match(provider,name,code);
        if(code.trim().matches("(?i)PK[0-9]+"))return match(provider,List.of(name),List.of(code),true);
        var codes=Normalizer.normalize(code,Normalizer.Form.NFKC).strip().split("\\R",-1);
        var names=Normalizer.normalize(name,Normalizer.Form.NFKC).strip().split("\\R",-1);
        if(codes.length<1||codes.length>2||codes.length!=names.length)
            return new Match("ambiguous","容鼎产品名称与A/C代码未逐行对应，需核对原表",null);
        var candidates=new ArrayList<String>();var labels=new HashSet<String>();var bases=new HashSet<String>();String commonName=null;
        for(int i=0;i<codes.length;i++) {
            var product=java.util.regex.Pattern.compile("(?i)^(PK[0-9]{4,6})\\s*\\(([AC])\\)$").matcher(codes[i].strip());
            if(!product.matches())return new Match("ambiguous","容鼎产品代码格式不明确，禁止猜测或仅按名称更新",null);
            String base=product.group(1),label=product.group(2).toUpperCase(Locale.ROOT);
            if(!labels.add(label)||!bases.add(base.toUpperCase(Locale.ROOT)))return new Match("ambiguous","容鼎A/C标签或基础产品代码重复，需核对原表",null);
            var channel=java.util.regex.Pattern.compile("(?i)^(.+)"+label+"(\\(SP\\))?$").matcher(names[i].strip());
            if(!channel.matches())return new Match("ambiguous","容鼎渠道名称与产品代码的A/C标签不一致，需核对原表",null);
            String stem=normalize(channel.group(1)+Objects.toString(channel.group(2),""));
            if(commonName!=null&&!commonName.equals(stem))return new Match("ambiguous","容鼎同格A/C渠道名称不属于同一价格组，需核对原表",null);
            commonName=stem;candidates.add(base);candidates.add(codes[i].strip());
        }
        return match(provider,Arrays.asList(names),candidates,true);
    }
    private Match match(String provider,List<String> names,List<String> codes,boolean requireCode) {
        if(unrestricted)return new Match("matched","兼容导入",null);
        var entries=providers.getOrDefault(normalize(provider),List.of());
        if(entries.isEmpty())return new Match("filtered","物流商不在公司清单",null);
        var byName=new LinkedHashMap<String,JsonNode>();var byCode=new LinkedHashMap<String,JsonNode>();
        for(var entry:entries) {
            if(names.stream().anyMatch(name->matches(name,entry.path("channelName"),entry.path("aliases"))))byName.put(entry.path("id").asText(),entry);
            if(codes.stream().anyMatch(code->!code.isBlank()&&matches(code,null,entry.path("productCodes"))))byCode.put(entry.path("id").asText(),entry);
        }
        if(byName.size()>1||byCode.size()>1)return new Match("ambiguous","同一物流商下匹配多个渠道",null);
        if(!byName.isEmpty()&&!byCode.isEmpty()&&!byName.keySet().equals(byCode.keySet()))
            return new Match("ambiguous","产品代码与渠道名称指向不同渠道",null);
        if(requireCode&&byCode.isEmpty())return new Match(byName.isEmpty()?"filtered":"ambiguous","容鼎基础产品代码未登记，未按名称猜测更新",null);
        var hits=byCode.isEmpty()?byName:byCode;
        if(hits.isEmpty())return new Match("filtered","渠道未登记或未登记该名称别名",null);
        var entry=hits.values().iterator().next();
        if(!entry.path("enabled").asBoolean(true))return new Match("filtered","公司渠道已停用",entry);
        return new Match("matched",requireCode?"容鼎完整产品代码（A/C标记单独核对）":byCode.isEmpty()?"渠道名称或登记别名":"原产品代码",entry);
    }
    private static boolean matches(String input,JsonNode name,JsonNode aliases) {
        var n=normalize(input);if(n.isBlank())return false;
        if(name!=null&&n.equals(normalize(name.asText())))return true;
        for(var alias:aliases)if(n.equals(normalize(alias.asText())))return true;
        return false;
    }
    /** Only exact registered identities in the title area count; never fuzzy-match notes. */
    public Match matchTitles(String provider,List<String> cells) {
        if(unrestricted)return null;
        var hits=new LinkedHashMap<String,JsonNode>();boolean explicit=false;
        for(var cell:cells)for(var line:cell.split("[\\r\\n]+")) {
            var text=normalize(line);
            var value=text.replaceFirst("^(?:渠道名称|渠道名|产品名称|渠道代码|产品代码|产品编码|渠道|产品)[:：]","");
            explicit|=!value.equals(text);
            var name=value.replaceFirst("(?:运价表|报价表|价格表)$","");
            var decision=match(provider,name,value);
            if(decision.status().equals("ambiguous"))return decision;
            if(decision.entry()!=null)hits.put(decision.entry().path("id").asText(),decision.entry());
        }
        if(hits.size()>1)return new Match("ambiguous","表内标题或产品代码指向多个渠道",null);
        if(hits.isEmpty())return explicit?new Match("filtered","表内渠道标题未登记，未使用工作表名称覆盖",null):null;
        var entry=hits.values().iterator().next();
        return new Match(entry.path("enabled").asBoolean(true)?"matched":"filtered","表内渠道标题或产品代码",entry);
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

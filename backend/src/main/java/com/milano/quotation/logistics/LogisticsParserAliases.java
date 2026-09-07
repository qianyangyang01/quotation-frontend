package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Version-controlled, deterministic header aliases. The workbook never supplies parsing rules. */
@Component
public class LogisticsParserAliases {
    public static final String RESOURCE="logistics-parser-aliases.yml";
    private final Scope common;
    private final Map<String,Scope> providers;

    public LogisticsParserAliases(){this(new ClassPathResource(RESOURCE));}
    LogisticsParserAliases(Resource resource){
        var factory=new YamlMapFactoryBean();factory.setResources(resource);var root=factory.getObject();
        if(root==null)throw new IllegalStateException("物流表头别名配置为空："+resource.getDescription());
        common=scope(map(root.get("common")),"common");providers=new LinkedHashMap<>();
        map(root.get("providers")).forEach((provider,value)->providers.put(normalize(provider),scope(map(value),"providers."+provider)));
    }

    public boolean matches(String provider,String field,String raw){
        var text=normalize(raw);if(text.isBlank())return false;var providerScope=providers.get(normalize(provider));
        if(providerScope!=null&&providerScope.matches(field,text,MatchKind.EXACT))return true;
        if(common.matches(field,text,MatchKind.EXACT))return true;
        if(providerScope!=null&&providerScope.matches(field,text,MatchKind.EXPRESSION))return true;
        return common.matches(field,text,MatchKind.EXPRESSION);
    }
    public String firstMatch(String provider,String raw,List<String> orderedFields){for(var field:orderedFields)if(matches(provider,field,raw))return field;return "";}
    public Optional<String> classify(String provider,String raw){
        var text=normalize(raw);if(text.isBlank())return Optional.empty();var providerScope=providers.get(normalize(provider));
        for(var priority:List.of(MatchKind.EXACT,MatchKind.EXPRESSION)) {
            var matches=new LinkedHashSet<String>();
            if(providerScope!=null)matches.addAll(providerScope.matches(text,priority));
            if(matches.isEmpty())matches.addAll(common.matches(text,priority));
            if(matches.size()>1)throw AppException.unprocessable("表头别名存在歧义："+raw+" -> "+matches);
            if(!matches.isEmpty())return Optional.of(matches.iterator().next());
        }
        return Optional.empty();
    }

    private static Scope scope(Map<String,Object> source,String path){
        var rules=new LinkedHashMap<String,Rule>();
        source.forEach((field,value)->rules.put(field,rule(map(value),path+"."+field)));
        var exactOwners=new HashMap<String,String>();var containsOwners=new HashMap<String,String>();
        rules.forEach((field,rule)->{
            for(var token:rule.exact){var prior=exactOwners.putIfAbsent(token,field);if(prior!=null&&!prior.equals(field))throw new IllegalStateException("物流表头精确别名冲突："+path+" 的 "+token+" 同时属于 "+prior+" 和 "+field);}
            for(var token:rule.contains){var prior=containsOwners.putIfAbsent(token,field);if(prior!=null&&!prior.equals(field))throw new IllegalStateException("物流表头包含别名冲突："+path+" 的 "+token+" 同时属于 "+prior+" 和 "+field);}
        });
        return new Scope(rules);
    }
    private static Rule rule(Map<String,Object> source,String path){
        var exact=strings(source.get("exact"));var contains=strings(source.get("contains"));var excludes=strings(source.get("excludeContains"));
        var regex=new ArrayList<Pattern>();for(var value:strings(source.get("regex")))try{regex.add(Pattern.compile(value,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));}catch(Exception e){throw new IllegalStateException("物流表头正则无效："+path+" -> "+value,e);}
        if(exact.isEmpty()&&contains.isEmpty()&&regex.isEmpty())throw new IllegalStateException("物流表头别名规则没有匹配项："+path);
        return new Rule(exact,contains,excludes,regex);
    }
    private static Map<String,Object> map(Object value){
        if(value==null)return Map.of();if(!(value instanceof Map<?,?> input))throw new IllegalStateException("物流表头别名配置结构错误");
        var output=new LinkedHashMap<String,Object>();input.forEach((key,item)->output.put(String.valueOf(key),item));return output;
    }
    private static Set<String> strings(Object value){
        if(value==null)return Set.of();var result=new LinkedHashSet<String>();
        if(value instanceof Collection<?> collection)collection.forEach(item->result.add(normalize(String.valueOf(item))));else result.add(normalize(String.valueOf(value)));
        result.remove("");return Collections.unmodifiableSet(result);
    }
    static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).trim().replaceAll("\\s+","").toLowerCase(Locale.ROOT);}

    private enum MatchKind {EXACT,EXPRESSION}
    private record Scope(Map<String,Rule> rules){
        Set<String> matches(String text,MatchKind kind){var result=new LinkedHashSet<String>();rules.forEach((field,rule)->{if(rule.matches(text,kind))result.add(field);});return result;}
        boolean matches(String field,String text,MatchKind kind){var rule=rules.get(field);return rule!=null&&rule.matches(text,kind);}
    }
    private record Rule(Set<String> exact,Set<String> contains,Set<String> excludes,List<Pattern> regex){
        boolean matches(String text,MatchKind kind){
            if(excludes.stream().anyMatch(text::contains))return false;
            if(kind==MatchKind.EXACT)return exact.contains(text);
            return contains.stream().anyMatch(text::contains)||regex.stream().anyMatch(pattern->pattern.matcher(text).matches());
        }
    }
}

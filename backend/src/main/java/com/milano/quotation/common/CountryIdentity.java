package com.milano.quotation.common;

import java.io.IOException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Shared country aliases; normalizes read projections without rewriting source prices. */
public final class CountryIdentity {
    private static final Map<String,List<String>> ALIASES = load();
    private CountryIdentity() {}
    private static Map<String,List<String>> load() {
        try(var source=CountryIdentity.class.getResourceAsStream("/country-aliases.json")) {
            if(source==null) throw new IllegalStateException("Missing country aliases");
            var result=new HashMap<String,List<String>>();
            for(var row:new JsonMapper().readTree(source)) {
                var aliases=new ArrayList<String>(); row.forEach(value->aliases.add(value.asText()));
                for(var alias:aliases) result.put(alias.toUpperCase(Locale.ROOT),List.copyOf(aliases));
            }
            return Map.copyOf(result);
        } catch(IOException error) { throw new IllegalStateException("Cannot load country aliases",error); }
    }
    public static String key(String value) {
        var normalized=value.trim().toUpperCase(Locale.ROOT); var aliases=ALIASES.get(normalized);
        return aliases==null?normalized:aliases.getFirst();
    }
    public static boolean same(String left,String right) { return !left.isBlank()&&!right.isBlank()&&key(left).equals(key(right)); }
    public static boolean matches(String code,String name,String country) { return same(code,country)||same(name,country); }
    public static String name(String code,String fallback) {
        var aliases=ALIASES.get(key(code)); if(aliases==null) aliases=ALIASES.get(key(fallback));
        return aliases==null?fallback:aliases.get(1);
    }
    public static List<String> variants(String value) { return ALIASES.getOrDefault(key(value),List.of(value)); }
}

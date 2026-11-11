package com.milano.quotation.logistics;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/** Europe has its own source layout and confirmations; US assumptions must not leak in. */
final class KuailvEuropeSourceRules {
    static final String NAME="欧洲小包-特货";
    static final String TITLE="欧洲小包-特货（免税）";
    // EU membership checked against the European Union country directory on 2026-11-11.
    static final Set<String> EU=Set.of("AT","BE","BG","HR","CY","CZ","DK","EE","FI","FR","DE","GR","HU","IE","IT","LV","LT","LU","MT","NL","PL","PT","RO","SK","SI","ES","SE");
    static final Set<String> EXCLUDED=Set.of("GB","NO","CH","RS");
    private static final Pattern BAND=Pattern.compile("(?i)^([0-9]+(?:\\.[0-9]+)?)-([0-9]+(?:\\.[0-9]+)?)(?:KG)?(?:\\(首重([0-9]+(?:\\.[0-9]+)?)KG\\))?$");
    record Band(BigDecimal from,BigDecimal to,BigDecimal minimum) {}
    static Band band(String raw) {
        String normalized=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC).replaceAll("\\s+","").replace('–','-').replace('—','-');
        var m=BAND.matcher(normalized);
        if(!m.matches())throw new IllegalArgumentException("欧洲小包重量段格式不明确："+raw);
        var from=new BigDecimal(m.group(1));var to=new BigDecimal(m.group(2));
        var minimum=m.group(3)==null?null:new BigDecimal(m.group(3));
        if(from.compareTo(to)>=0||minimum!=null&&(minimum.signum()<=0||minimum.compareTo(to)>0))
            throw new IllegalArgumentException("欧洲小包重量范围或首重无效："+raw);
        return new Band(from,to,minimum);
    }
    private KuailvEuropeSourceRules() {}
}

package com.milano.quotation.logistics;

import java.util.*;

/** Old codes are conflict evidence, never a whitelist for future source revisions. */
final class LogisticsProductIdentity {
    private LogisticsProductIdentity() {}
    static boolean codeConflict(String provider,String name,String code) {
        if(code.isBlank())return false;
        String n=CompanyChannelScope.normalize(name),c=CompanyChannelScope.normalize(code);
        if(provider.equals("云途"))for(var p:YunexpressAdditionalRules.PRODUCTS)
            if(c.equals(CompanyChannelScope.normalize(p.code()))&&!n.equals(CompanyChannelScope.normalize(p.name())))return true;
        var extra=YanwenWanbangAdditionalRules.named(provider,name);
        if(extra!=null)for(var p:YanwenWanbangAdditionalRules.PRODUCTS)
            if(p.provider().equals(provider)&&c.equals(CompanyChannelScope.normalize(p.code()))&&!p.name().equals(extra.name()))return true;
        if(provider.equals("巧捷"))for(var p:QiaojieSourceRules.PRODUCTS)
            if(c.equals(CompanyChannelScope.normalize(p.code()))&&!n.equals(CompanyChannelScope.normalize(p.name())))return true;
        if(provider.equals("顺友"))for(var p:SunyouSourceRules.PRODUCTS)
            if(c.equals(CompanyChannelScope.normalize(p.code()))&&!n.equals(CompanyChannelScope.normalize(p.name())))return true;
        if(provider.equals("百州")&&n.equals(CompanyChannelScope.normalize("BZ-欧美服装专线挂号"))&&c.equals("bz504"))return true;
        if(provider.equals("捷易通达"))return c.equals("1961")&&!n.equals("以色列敏感")||c.equals("2001")&&!n.equals("以色列敏感(到门)");
        if(provider.equals("极通环球")&&JitongPureBatteryRules.named(name)&&c.equals("jt-hq-cd"))return true;
        return false;
    }
    static Set<String> attributes(String value) {
        var result=new HashSet<String>();String n=CompanyChannelScope.normalize(value);
        for(var pair:List.of(new String[]{"普货","general"},new String[]{"服装","general"},new String[]{"服饰","general"},
                new String[]{"带电","battery"},new String[]{"含电","battery"},new String[]{"纯电","battery"},
                new String[]{"化妆品","cosmetics"},new String[]{"敏感","sensitive"},new String[]{"特敏","sensitive"},new String[]{"保健品","supplement"}))
            if(n.contains(pair[0]))result.add(pair[1]);
        return result;
    }
    static boolean attributeConflict(String sourceName,String explicitAttribute) {
        if(explicitAttribute.isBlank())return false;
        // Only a dedicated concise attribute value counts; descriptive restrictions are not an attribute declaration.
        String raw=CompanyChannelScope.normalize(explicitAttribute);
        if(!raw.matches("(?:只能|可走)?(?:普货|服装|服饰|带电|含电|纯电|化妆品|非液体化妆品|敏感货?|特敏货?|保健品)(?:[、,，/及和+](?:普货|服装|服饰|带电|含电|纯电|化妆品|非液体化妆品|敏感货?|特敏货?|保健品))*"))return false;
        var expected=attributes(sourceName);var actual=attributes(raw);
        return !expected.isEmpty()&&!actual.isEmpty()&&Collections.disjoint(expected,actual);
    }
    static boolean nameAttributeConflict(String sourceName,String registeredName) {
        var source=attributes(sourceName);var registered=attributes(registeredName);
        return !source.isEmpty()&&!registered.isEmpty()&&Collections.disjoint(source,registered);
    }
}

package com.milano.quotation.common;

public final class LogisticsAttributes {
    private LogisticsAttributes() {}
    public static String normalize(String value) {
        var name=value==null?"":value.trim();
        return name.equals("纯电池")?"纯电":name;
    }
}

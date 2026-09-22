package com.milano.quotation.logistics;

import java.util.List;

/** Only the three products requested from the Sunyou catalogue. No tariff constants. */
final class SunyouSourceRules {
    static final String PROVIDER="顺友";
    record Product(String name,String code,String sheet,List<String> titles) {}
    static final List<Product> PRODUCTS=List.of(
        new Product("顺邮宝挂号","SYBRAM","顺邮宝挂",List.of("顺邮宝挂号价目表","顺邮宝挂号")),
        new Product("顺速宝(特货)","SSBRAM","顺速宝特",List.of("顺速宝特货价目表","顺速宝(特货)","顺速宝特货")),
        new Product("顺速宝Plus","SYEPL","顺速宝Plus",List.of("顺速宝Plus价目表","顺速宝Plus"))
    );
    static Product title(String text) {
        var normalized=CompanyChannelScope.normalize(text);
        return PRODUCTS.stream().filter(p->p.titles().stream().anyMatch(t->CompanyChannelScope.normalize(t).equals(normalized))).findFirst().orElse(null);
    }
    static boolean selectedSheet(String name) {return PRODUCTS.stream().anyMatch(p->p.sheet().equals(name));}
    private SunyouSourceRules() {}
}

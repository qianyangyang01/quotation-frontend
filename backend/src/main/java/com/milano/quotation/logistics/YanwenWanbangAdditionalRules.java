package com.milano.quotation.logistics;

import java.util.List;

/** Product identity only; prices and minimum weights always come from the uploaded workbook. */
final class YanwenWanbangAdditionalRules {
    record Product(String provider,String name,String alias,String code) {}
    static final List<Product> PRODUCTS=List.of(
        new Product("燕文","燕文精品服装专线-普货","","1667"),
        new Product("燕文","燕文大货专线追踪-特货","","1558"),
        new Product("燕文","燕文大货专线追踪-普货","","1557"),
        new Product("万邦","万邦大货专线挂号含电","万邦大货专线含电","WBSLLP"));
    private YanwenWanbangAdditionalRules() {}
    static Product named(String provider,String name) {
        var normalized=CompanyChannelScope.normalize(name);
        if(provider.equals("万邦")&&normalized.equals(CompanyChannelScope.normalize("万邦速达大货专线含电报价表(RMB)")))return PRODUCTS.get(3);
        return PRODUCTS.stream().filter(p->p.provider.equals(provider)&&(CompanyChannelScope.normalize(p.name).equals(normalized)
            ||(!p.alias.isBlank()&&CompanyChannelScope.normalize(p.alias).equals(normalized)))).findFirst().orElse(null);
    }
}

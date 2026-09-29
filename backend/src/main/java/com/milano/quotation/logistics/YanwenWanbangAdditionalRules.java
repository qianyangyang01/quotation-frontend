package com.milano.quotation.logistics;

import java.util.List;

/** Product identity only; prices and minimum weights always come from the uploaded workbook. */
final class YanwenWanbangAdditionalRules {
    record Product(String provider,String name,String alias,String code) {}
    static final List<Product> PRODUCTS=List.of(
        new Product("燕文","燕文专线快递-普货","","440"),
        new Product("燕文","燕文专线快递-特货","","557"),
        new Product("燕文","燕文精品服装专线-普货","","1667"),
        new Product("燕文","燕文大货专线追踪-特货","","1558"),
        new Product("燕文","燕文大货专线追踪-普货","","1557"),
        new Product("万邦","万邦大货专线挂号含电","万邦大货专线含电","WBSLLP"),
        new Product("万邦","万邦中包专线挂号含电","万邦中包专线含电","WBSLMP"),
        new Product("万邦","万邦中包专线挂号普货","万邦中包专线普货","WBSLMPPH"));
    private YanwenWanbangAdditionalRules() {}
    static Product named(String provider,String name) {
        var normalized=CompanyChannelScope.normalize(name);
        return PRODUCTS.stream().filter(p->p.provider.equals(provider)&&(CompanyChannelScope.normalize(p.name).equals(normalized)
            ||(!p.alias.isBlank()&&CompanyChannelScope.normalize(p.alias).equals(normalized))
            ||(provider.equals("万邦")&&List.of(p.name,p.alias).stream().anyMatch(label->
                CompanyChannelScope.normalize(label.replaceFirst("^万邦","万邦速达")+"报价表(RMB)").equals(normalized)))))
            .findFirst().orElse(null);
    }
}

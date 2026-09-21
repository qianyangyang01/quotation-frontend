package com.milano.quotation.logistics;

import java.util.List;

final class YunexpressAdditionalRules {
    record Product(String name,String code,boolean large) {}
    static final List<Product> PRODUCTS=List.of(
        new Product("云途全球专线挂号（标快带电）","BKZXR",false),
        new Product("云途全球专线挂号（标快普货）","BKPHR",false),
        new Product("云途大货18000专线挂号（特惠带电）","DHZXR",true),
        new Product("云途大货18000专线挂号（特惠普货）","DHZXRPH",true));
    private YunexpressAdditionalRules() {}
    static Product named(String name){return PRODUCTS.stream().filter(p->CompanyChannelScope.normalize(p.name).equals(CompanyChannelScope.normalize(name))).findFirst().orElse(null);}
}

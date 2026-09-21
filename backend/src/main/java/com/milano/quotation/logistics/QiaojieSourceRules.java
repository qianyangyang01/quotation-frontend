package com.milano.quotation.logistics;

import java.util.List;

/** Exact company-approved products; similarly named E/M/A/F services are not interchangeable. */
final class QiaojieSourceRules {
    static final String PROVIDER="巧捷";
    record Product(String name,String code,String attribute,boolean perPiece) {}
    static final List<Product> PRODUCTS=List.of(
            new Product("巧捷专线小包全球特惠F(敏感)","QEGBTF","敏感货",false),
            new Product("巧捷小包全球商派E(服装)","QEUBF","普货",false),
            new Product("巧捷小包全球特惠A(服装)","QEGBFA","普货",false),
            new Product("巧捷小包快递特惠包税B","QEUFAB","普货",true));
    private QiaojieSourceRules() {}
    static Product named(String text) {
        var normalized=CompanyChannelScope.normalize(text);
        return PRODUCTS.stream().filter(p->normalized.equals(CompanyChannelScope.normalize(p.name()))
                ||normalized.equals(CompanyChannelScope.normalize(p.name()+p.code()))).findFirst().orElse(null);
    }
    static boolean allowed(String name,String code) {
        var product=named(name);
        return product!=null&&(code.isBlank()||CompanyChannelScope.normalize(code).equals(CompanyChannelScope.normalize(product.code())));
    }
}

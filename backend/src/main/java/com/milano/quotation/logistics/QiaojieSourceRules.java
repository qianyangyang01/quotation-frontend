package com.milano.quotation.logistics;

import java.util.List;
import tools.jackson.databind.JsonNode;
import com.milano.quotation.common.AppException;

/** Exact company-approved products; similarly named E/M/A/F services are not interchangeable. */
final class QiaojieSourceRules {
    static final String PROVIDER="巧捷";
    static final String US_ONLY_COMPANY_ID="0c9f62ef-f7d8-48ea-b79b-fca3672fd52e";
    static final String US_ONLY_CHANNEL_CODE="C-44c46c277fd0a965da19";
    static final String US_ONLY_REASON="巧捷小包全球特惠A(服装)仅限美国，其他国家不导入";
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
    static boolean usOnly(String name,String code,String companyId) {
        var product=named(name);
        return US_ONLY_COMPANY_ID.equals(companyId)
                || CompanyChannelScope.normalize(US_ONLY_CHANNEL_CODE).equals(CompanyChannelScope.normalize(code))
                || "qegbfa".equals(CompanyChannelScope.normalize(code))
                || product!=null&&product.code().equals("QEGBFA");
    }
    static void validateCountries(String provider,String name,String code,JsonNode payload) {
        boolean restricted=US_ONLY_CHANNEL_CODE.equalsIgnoreCase(code)||US_ONLY_COMPANY_ID.equals(payload.path("companyChannelId").asText())
                || PROVIDER.equals(provider)&&(usOnly(name,code,"")||payload.path("rows").valueStream()
                    .anyMatch(r->usOnly("",r.path("sourceProductCode").asText(),"")));
        if(!restricted)return;
        if(!payload.path("rows").isArray()||payload.path("rows").isEmpty()
                ||payload.path("rows").valueStream().anyMatch(r->!"US".equalsIgnoreCase(r.path("countryCode").asText())))
            throw AppException.unprocessable("巧捷小包全球特惠A(服装)只能发布美国价格，且至少需要一条有效美国价格");
    }
}

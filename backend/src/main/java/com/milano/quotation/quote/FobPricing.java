package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import java.math.MathContext;
import com.milano.quotation.fob.FobInvoicePolicy;
import java.util.Set;

/** Only this server-side policy owns classification coefficients. */
public final class FobPricing {
    public static final int POLICY_VERSION = 4;
    public static final String KEY = "fob-pricing";
    public static final Set<String> CLASSES = Set.of("rebate-fob", "rebate-cfr-cif", "ordinary-fob");
    public static ObjectNode defaults() {
        return FobQuotation.object().put("rebate-fob", new BigDecimal("1.08"))
            .put("rebate-cfr-cif", new BigDecimal("1.11")).put("ordinary-fob", new BigDecimal("1.20"));
    }
    public static void validate(JsonNode policy) {
        if (!policy.isObject() || policy.size()!=3) throw AppException.unprocessable("必须分别填写三类 FOB 系数");
        for (var key:CLASSES) {
            var n=policy.path(key);
            if (!n.isNumber() || !Double.isFinite(n.asDouble()) || n.decimalValue().signum()<=0 || n.decimalValue().compareTo(BigDecimal.TEN)>0 || n.decimalValue().scale()>6)
                throw AppException.unprocessable("报价系数须大于0、不超过10，最多6位小数");
        }
    }
    static ObjectNode configuration(JdbcClient jdbc,ObjectMapper mapper) {
        var rows=jdbc.sql("select payload,version from finance_setting where setting_key='fob-pricing' for share")
            .query((rs,n)-> {var out=FobQuotation.object().put("version",rs.getLong("version"));out.set("value",mapper.readTree(rs.getString("payload")));return out;}).list();
        if (rows.isEmpty()) {var out=FobQuotation.object().put("version",-1);out.set("value",defaults());return out;}
        validate(rows.get(0).path("value"));return rows.get(0);
    }
    static String classification(JsonNode product) {
        var value=product.path("classification").asText();
        if (!CLASSES.contains(value)) throw AppException.unprocessable("请选择有效的报价分类");
        return value;
    }
    static void invoice(JsonNode raw,String kind) {
        if (kind.equals("ordinary-fob")) return;
        if (!FobInvoicePolicy.special(raw.path("invoiceType").asText()))
            throw new AppException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"FOB_SPECIAL_INVOICE_REQUIRED","采购资料不是已确认专票，请联系采购核实票据类型及含专票采购价后重新查询");
    }
    private static void invalid(String message) {
        throw new AppException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"FOB_INVOICE_PRICE_INVALID",message+"；请联系采购核实后重新查询");
    }
    private static BigDecimal positive(JsonNode node,String label) {
        if(!node.isNumber()||!Double.isFinite(node.asDouble())||node.decimalValue().signum()<=0||node.decimalValue().compareTo(new BigDecimal("1000000000"))>0)invalid("缺少有效的"+label+"（必须大于0）");
        return node.decimalValue();
    }
    static boolean missingInvoice(JsonNode raw) {
        return Set.of("","未确认","待确认","暂无数据","未填写").contains(raw.path("invoiceType").asText("").trim());
    }
    static boolean missingTaxPoint(JsonNode raw) {
        var tax=raw.path("taxPoint");
        return tax.isMissingNode()||tax.isNull()||tax.isTextual()&&tax.asText().isBlank();
    }
    /** Derive quote-time ticket costs without changing procurement tiers or source invoice fields. */
    static void applyPurchase(ObjectNode product,JsonNode raw) {
        var kind=classification(product);
        if(!missingInvoice(raw))invoice(raw,kind);
        var parsed=product.path("parsed");var tiers=parsed.path("priceTiers");
        if(!tiers.isArray()||tiers.isEmpty()||tiers.size()>100)invalid("采购阶梯缺失或超过100档");
        long moq=FobQuotation.count(parsed.path("minOrderQty"));FobQuotation.count(parsed.path("orderMultiple"));
        long previous=0;BigDecimal previousPrice=null;
        for(var tier:tiers){
            long min=FobQuotation.count(tier.path("minQty"));
            Long max=tier.path("maxQty").isNull()?null:FobQuotation.count(tier.path("maxQty"));
            if(min<moq||min<=previous||max!=null&&max<min)invalid("采购阶梯区间重叠或倒置");
            var price=positive(tier.path("unitPriceCny"),min+"件起阶梯采购价");
            if(previousPrice!=null&&price.compareTo(previousPrice)>0)invalid("采购阶梯价格随数量上升，需核对档位");
            previous=max==null?Long.MAX_VALUE:max;previousPrice=price;
        }
        if(tiers.get(0).path("minQty").asLong()!=moq)invalid("起步价格没有对应起订量");
        var base=positive(tiers.get(0).path("unitPriceCny"),"起步采购原价");
        var basis=raw.path("purchasePriceBasis").asText();
        if(!Set.of("","quoted","tax_included").contains(basis))invalid("采购价格口径无法识别");
        var policy=product.putObject("invoicePricing").put("basePurchaseCny",base).put("policyVersion",POLICY_VERSION);
        if(kind.equals("ordinary-fob")){
            boolean included=basis.equals("tax_included");
            var factor=included?BigDecimal.ONE:new BigDecimal("1.10");
            policy.put("mode",included?"included":"ordinary-fixed-ticket").put("fobInvoiceRatio",factor)
                .put("baseIncludedCny",base.multiply(factor)).put("effectiveTaxPoint",new BigDecimal("0.10"))
                .put("verificationRequired",false).put("ruleSource","ordinary-original-rule");
            return;
        }
        boolean missingType=missingInvoice(raw),missingPoint=missingTaxPoint(raw);
        boolean needsVerification=missingType||missingPoint;
        BigDecimal suppliedTax=null;
        if(!missingPoint){
            var tax=raw.path("taxPoint");
            if(!tax.isNumber()||!Double.isFinite(tax.asDouble())||tax.decimalValue().signum()<0||tax.decimalValue().compareTo(BigDecimal.ONE)>0)
                invalid("票点必须为0至1的有效数值（13%填写0.13）");
            suppliedTax=tax.decimalValue();
        }
        var tax=needsVerification?new BigDecimal("0.10"):suppliedTax;
        BigDecimal included=raw.hasNonNull("taxIncludedPriceCny")?positive(raw.path("taxIncludedPriceCny"),"起步含专票价"):null;
        if(basis.equals("tax_included")){
            if(included!=null&&included.compareTo(base)!=0)invalid("已含票阶梯的起步价与独立含票价不一致，禁止重复换算");
            policy.put("mode","included").put("baseIncludedCny",base).put("fobInvoiceRatio",BigDecimal.ONE);
        }else{
            if(included!=null){
                if(included.compareTo(base)<0)invalid("起步含票价低于起步采购原价");
                if(included.compareTo(base.multiply(BigDecimal.valueOf(2)))>0)invalid("起步含票价超过原价2倍，需核对资料");
                if(suppliedTax!=null&&included.subtract(base.multiply(BigDecimal.ONE.add(suppliedTax))).abs().compareTo(new BigDecimal("0.005"))>0)
                    invalid("起步含票价与实际票点计算不一致");
                policy.put("sourceIncludedCny",included);
            }
            policy.put("mode",needsVerification?"default-tax-point":"actual-tax-point")
                .put("baseIncludedCny",base.multiply(BigDecimal.ONE.add(tax))).put("fobInvoiceRatio",BigDecimal.ONE.add(tax));
        }
        policy.put("effectiveTaxPoint",tax).put("verificationRequired",needsVerification).put("procurementUnchanged",true)
            .put("ruleSource",needsVerification?"missing-invoice-default":"procurement-tax-point")
            .put("basisLabel",needsVerification?(basis.equals("tax_included")?"票据资料待核实 · 已含票阶梯直接使用":"票据资料待核实 · 系统默认按10点专票计价"):"按数据源实际专票类型和票点计价");
        if(suppliedTax!=null)policy.put("sourceTaxPoint",suppliedTax);
        if(needsVerification)policy.put("assumedTaxPoint",new BigDecimal("0.10"));
        var missing=policy.putArray("missingFields");if(missingType)missing.add("票类型");if(missingPoint)missing.add("票点");
        // No dismissal flag or procurement writes: every lookup re-derives this from the selected source.
    }

    static BigDecimal purchaseCost(JsonNode product,BigDecimal purchase) {
        var policy=product.path("invoicePricing");
        if(Set.of("actual-tax-point","default-tax-point","ordinary-fixed-ticket").contains(policy.path("mode").asText()))return purchase.multiply(BigDecimal.ONE.add(policy.path("effectiveTaxPoint").decimalValue()));
        if(policy.path("mode").asText().equals("legacy-ten-point"))return purchase.multiply(new BigDecimal("1.10"));
        return policy.path("mode").asText().equals("ratio")
            ? purchase.multiply(policy.path("baseIncludedCny").decimalValue()).divide(policy.path("basePurchaseCny").decimalValue(),MathContext.DECIMAL128)
            : purchase;
    }
    static ObjectNode pricing(ObjectNode product,BigDecimal rate,JsonNode configuration) {
        var kind=classification(product);var factor=configuration.path("value").path(kind).decimalValue();
        var out=FobQuotation.object().put("version",configuration.path("version").asLong()).put("policyVersion",POLICY_VERSION).put("rate",rate).put("fobCoefficient",factor);
        var rows=out.putArray("rows");var parsed=product.path("parsed");var freight=FobQuotation.amount(parsed.at("/freight/unitFreightCny"));
        long multiple=FobQuotation.count(parsed.path("orderMultiple"));
        for (var tier:parsed.path("priceTiers")) {
            var purchase=FobQuotation.amount(tier.path("unitPriceCny"));var included=purchaseCost(product,purchase);var cost=included.add(freight);
            long min=FobQuotation.count(tier.path("minQty"));Long max=tier.hasNonNull("maxQty")?FobQuotation.count(tier.path("maxQty")):null;
            var row=FobQuotation.object().put("minQty",min).put("unit",tier.path("unit").asText("件")).put("purchaseCny",purchase)
                .put("taxIncludedCny",included).put("freightCny",freight).put("costCny",cost)
                .put("declaredUsd",FobQuotation.price(cost,0,factor.toPlainString(),rate)).put("undeclaredUsd",FobQuotation.price(cost,0,factor.toPlainString(),rate))
                .put("smallOrderUsd",FobQuotation.price(cost,1,factor.toPlainString(),rate));
            if(max==null)row.putNull("maxQty");else row.put("maxQty",max);
            Long threshold=null;
            if(cost.signum()>0){var target=BigDecimal.valueOf(200).divide(cost,0,java.math.RoundingMode.CEILING).max(BigDecimal.valueOf(min));if(target.compareTo(BigDecimal.valueOf(9007199254740991L-multiple))<=0){long q=((target.longValue()+multiple-1)/multiple)*multiple;if(max==null||q<=max)threshold=q;}}
            if(threshold==null)row.putNull("thresholdQty");else row.put("thresholdQty",threshold);rows.add(row);
        }
        return out;
    }
    public static void requireClassification(JsonNode request) {
        var snapshot=request.path("fob");
        if(snapshot.path("schemaVersion").asInt()==2) {for(var item:snapshot.path("items"))classification(item.path("product"));}
        else classification(snapshot.path("product"));
    }
    private FobPricing() {}
}

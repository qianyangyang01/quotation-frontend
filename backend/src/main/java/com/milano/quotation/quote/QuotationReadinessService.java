package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.finance.FinanceSettingRepository;
import com.milano.quotation.logistics.LogisticsService;
import com.milano.quotation.purchase.PurchaseProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

@Service
public class QuotationReadinessService {
    private static final List<String> FINANCE_KEYS = List.of(
            "country-classification", "channel-policies", "customer-grades", "exchange-rate", "tax-settings");
    private final PurchaseProductService products;
    private final LogisticsService logistics;
    private final FinanceSettingRepository finance;

    public QuotationReadinessService(PurchaseProductService products, LogisticsService logistics,
                                     FinanceSettingRepository finance) {
        this.products = products;
        this.logistics = logistics;
        this.finance = finance;
    }

    @Transactional(readOnly = true)
    public ObjectNode snapshot() {
        var root = JsonNodeFactory.instance.objectNode();
        var missing = root.putArray("missing");
        var readyProducts = products.readyCount();
        var publishedChannels = logistics.publishedChannelCount();
        root.putObject("purchase").put("ready", readyProducts > 0).put("readyProducts", readyProducts);
        root.putObject("logistics").put("ready", publishedChannels > 0).put("publishedChannels", publishedChannels);
        if (readyProducts == 0) missing.add("至少需要1个已确认转正式的采购商品");
        if (publishedChannels == 0) missing.add("至少需要1个已审核发布的物流渠道");

        var financeNode = root.putObject("finance");
        var missingKeys = financeNode.putArray("missingKeys");
        for (var key : FINANCE_KEYS) {
            var row = finance.findById(key);
            if (row.isEmpty() || !completeFinance(key, row.get().payload)) missingKeys.add(key);
        }
        financeNode.put("ready", missingKeys.isEmpty()).put("configured", FINANCE_KEYS.size() - missingKeys.size());
        if (!missingKeys.isEmpty()) missing.add("五类财务配置尚未完整保存");
        root.put("ready", missing.isEmpty());
        return root;
    }

    @Transactional(readOnly = true)
    public void assertCanCreate(ObjectNode quotation) {
        var state = snapshot();
        var reasons = new ArrayList<String>();
        state.path("missing").forEach(item -> reasons.add(item.asText()));
        var skuText = quotation.path("primarySku").asText("").trim();
        if (skuText.isEmpty()) {
            reasons.add("报价必须选择正式采购商品");
        } else {
            for (var sku : skuText.split("[,，、\\s]+")) {
                if (!sku.isBlank() && !products.isQuoteReady(sku)) reasons.add("商品 " + sku + " 尚未确认转正式");
            }
        }
        if (!reasons.isEmpty()) throw AppException.unprocessable("报价业务尚未就绪：" + String.join("；", reasons.stream().distinct().toList()));
    }

    private static boolean completeFinance(String key, JsonNode payload) {
        if (payload == null || payload.isNull()) return false;
        return switch (key) {
            case "country-classification", "channel-policies", "customer-grades" -> payload.isArray() && !payload.isEmpty();
            case "exchange-rate" -> payload.isObject() && payload.path("usdCny").asDouble(0) > 0;
            case "tax-settings" -> payload.isObject()
                    && payload.path("countries").isArray() && !payload.path("countries").isEmpty()
                    && payload.path("providers").isArray() && !payload.path("providers").isEmpty()
                    && !payload.path("updatedAt").asText("").isBlank()
                    && !payload.path("updatedAt").asText("").contains("尚未保存");
            default -> false;
        };
    }
}

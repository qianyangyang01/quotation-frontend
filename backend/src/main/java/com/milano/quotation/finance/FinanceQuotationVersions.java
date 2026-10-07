package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.LinkedHashMap;

/** Called inside quotation creation's transaction, before locking other finance rows. */
public final class FinanceQuotationVersions {
    private FinanceQuotationVersions() {}
    static final List<String> KEYS = List.of("country-classification", "channel-policies", "customer-grades",
            "exchange-rate", "tax-settings", "surcharge-settings", "customer-operation-fees", "freight-discount-settings");

    public static void validate(JdbcClient jdbc, JsonNode quotation) {
        // Older clients did not send this snapshot; keep their existing validation path.
        if (!quotation.has("financeVersions")) return;
        var expected = quotation.path("financeVersions");
        boolean oldClient = !expected.has("freight-discount-settings");
        if (!expected.isObject() || expected.size() != KEYS.size() - (oldClient ? 1 : 0))
            throw AppException.unprocessable("缺少完整财务版本信息，请刷新后重新计价");
        for (var key : KEYS) {
            if (oldClient && key.equals("freight-discount-settings")) continue;
            var value = expected.path(key);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < -1)
                throw AppException.unprocessable("财务版本信息无效，请刷新后重新计价");
        }
        var actual = new LinkedHashMap<String, Long>();
        jdbc.sql("select setting_key,version from finance_setting where setting_key in (:keys) order by setting_key for share")
                .param("keys", KEYS).query((rs, n) -> { actual.put(rs.getString(1),rs.getLong(2)); return 0; }).list();
        for (var key : KEYS) if (actual.getOrDefault(key,-1L) != expected.path(key).asLong(-1))
            throw AppException.conflict("财务设置已更新，请更新报价后再保存");
    }
}

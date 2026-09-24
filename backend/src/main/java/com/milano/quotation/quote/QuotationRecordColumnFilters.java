package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Apply column criteria before both aggregation and pagination; export uses the same query. */
final class QuotationRecordColumnFilters {
    private QuotationRecordColumnFilters() {}

    // Match frontend normalization, including pre-quoteOptions records.
    private static final String OPTIONS = """
        (case when jsonb_typeof(payload->'quoteOptions')='array' then payload->'quoteOptions'
          when jsonb_typeof(payload->'specifiedQuotes')='array' then coalesce(
            (select jsonb_agg(value-'id') from jsonb_array_elements(payload->'specifiedQuotes')),
            jsonb_build_array(jsonb_build_object('id','legacy-'||id::text||'-primary')))
          else jsonb_build_array(jsonb_build_object('id','legacy-'||id::text||'-primary')) end)
        """;

    static void append(StringBuilder where, Map<String,Object> params, QuotationRecordQuery.Filters filters) {
        validate(filters.optionScale(), Set.of("single", "multiple"), "报价规模不合法");
        validate(filters.priceDifference(), Set.of("lower", "equal", "higher", "missing"), "报价差异不合法");
        text(where, params, "product", filters.product(), """
            concat_ws(' ',quote_no,payload->>'primarySku',payload->>'productSummary',payload->>'salespersonName',
              (select string_agg(concat_ws(' ',b->>'sku',b->>'name'),' ') from jsonb_array_elements(
                case when jsonb_typeof(payload->'bundleItems')='array' then payload->'bundleItems' else '[]'::jsonb end) b))
            """);
        text(where, params, "customer", filters.customer(), "coalesce(payload->>'customerName','')");
        text(where, params, "channel", filters.channel(), "concat_ws(' ',payload->>'carrier',payload->>'channel',payload->>'rule',"
            + "(select string_agg(concat_ws(' ',o->>'carrier',o->>'channel',o->>'channelCode',o->>'rule'),' ') from jsonb_array_elements("+OPTIONS+") o))");
        if (present(filters.optionScale())) where.append(" and jsonb_array_length(").append(OPTIONS).append(")")
            .append(filters.optionScale().equals("multiple") ? ">1" : "<=1");
        if (present(filters.priceDifference())) {
            var condition = switch (filters.priceDifference()) {
                case "lower" -> "customer_price < system_price";
                case "higher" -> "customer_price > system_price";
                case "missing" -> "(customer_price is null) <> (system_price is null)";
                default -> "customer_price is distinct from system_price";
            };
            // Equal means no changed cell. Lower/higher match any cell and can both match a mixed record.
            where.append(filters.priceDifference().equals("equal") ? " and not exists(" : " and exists(")
                .append("select 1 from (").append(comparisons()).append(") prices where ").append(condition).append(")");
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void validate(String value, Set<String> allowed, String message) {
        if (present(value) && !allowed.contains(value)) throw AppException.unprocessable(message);
    }
    private static void text(StringBuilder where, Map<String,Object> params, String key, String value, String expression) {
        if (!present(value)) return;
        where.append(" and position(:").append(key).append(" in lower(").append(expression).append("))>0");
        params.put(key, value.trim().toLowerCase(Locale.ROOT));
    }
    private static String array(String expression) {
        return "(case when jsonb_typeof("+expression+")='array' then "+expression+" else '[]'::jsonb end)";
    }
    private static String number(String expression) {
        var value="("+expression+")";
        var text="("+value+" #>> '{}')";
        return "case when "+value+" is null or "+value+"='null'::jsonb or "+value+"='\"\"'::jsonb then null"
            + " when jsonb_typeof("+value+")='number' then "+text+"::numeric"
            + " when jsonb_typeof("+value+")='string' and btrim("+text+") ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$' then "+text+"::numeric"
            + " when "+value+"='true'::jsonb then 1 else 0 end";
    }
    private static String comparisons() {
        // Use saved quantity snapshots before legacy option prices, exactly as savedSystemPrice does.
        return "select "+number("customer_json")+" customer_price,"+number("system_json")+" system_price from ("
            + "select c.value->'prices'->(q.ordinality::int-1) customer_json, "
            + "case when s.value is not null and sq.ordinality is not null then s.value->'prices'->(sq.ordinality::int-1) else o.value->(case q.value::text::bigint when 1 then 'quote1Usd' when 2 then 'quote2Usd' when 3 then 'quote3Usd' else case when q.value::text::bigint=coalesce((payload->>'customQuoteQuantity')::bigint,0) then 'quoteCustomUsd' end end) end system_json"
            + " from lateral (select coalesce(nullif(payload->'customerQuote','null'::jsonb),nullif(payload->'sheetQuote','null'::jsonb)) snapshot) snap"
            + " cross join lateral jsonb_array_elements("+array("snap.snapshot->'rows'")+") c(value)"
            + " join lateral jsonb_array_elements("+OPTIONS+") with ordinality o(value,ordinality)"
            + " on coalesce(nullif(o.value->>'id',''),'legacy-'||id::text||'-'||(o.ordinality-1))=c.value->>'optionId'"
            + " cross join lateral jsonb_array_elements("+array("snap.snapshot->'quantities'")+") with ordinality q(value,ordinality)"
            + " left join lateral (select value from jsonb_array_elements("+array("payload->'systemQuantityQuotes'->'rows'")+") r(value) where r.value->>'optionId'=c.value->>'optionId' limit 1) s on true"
            + " left join lateral (select ordinality from jsonb_array_elements("+array("payload->'systemQuantityQuotes'->'quantities'")+") with ordinality quantities(value,ordinality) where quantities.value=q.value limit 1) sq on true) cells";
    }
}

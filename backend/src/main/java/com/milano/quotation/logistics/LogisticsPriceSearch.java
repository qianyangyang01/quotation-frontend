package com.milano.quotation.logistics;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Literal keyword matching for the price list and its export; this never selects quotation rates. */
final class LogisticsPriceSearch {
    private LogisticsPriceSearch() {}

    static List<String> terms(String query) {
        if (query == null) return List.of();
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("(?U)\\s+"))
                .filter(term -> !term.isEmpty()).distinct().toList();
    }

    // Keep equivalent to the channel_base predicate in LogisticsDatasetService.prices.
    // Every term must match. A term may combine the full provider name with a channel fragment,
    // e.g. 云途化妆品 matches 云途 / 云途全球化妆品类专线挂号 without requiring the intervening 全球.
    static boolean matches(List<String> terms, String provider, String channel) {
        String providerName = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String channelName = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        String fullName = providerName + channelName;
        return terms.stream().allMatch(term -> fullName.contains(term)
                || (!providerName.isEmpty() && term.contains(providerName)
                    && channelName.contains(term.replace(providerName, ""))));
    }
}

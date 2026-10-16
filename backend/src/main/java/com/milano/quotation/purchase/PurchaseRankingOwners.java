package com.milano.quotation.purchase;

import java.util.*;

/** Ranking-only identity resolution; never changes accounts or procurement records. */
final class PurchaseRankingOwners {
    record Account(String account, String name, String role) {}
    // Business-confirmed legacy aliases. Bind to account, never infer from a surname.
    private static final Map<String,String> ALIASES = Map.ofEntries(
        Map.entry("黄","HUANGCHUANZHEN"), Map.entry("支","ZHIXINYU"),
        Map.entry("周","ZHOUYUANYUAN"), Map.entry("章","ZHANGPANPAN"),
        Map.entry("杨","YANGJIAOJIAO"), Map.entry("樊","FANDONGLING"),
        Map.entry("谢","XIEGANG"), Map.entry("郑","ZHENGKE"),
        Map.entry("方","FANGRENJUAN"), Map.entry("唐","TANGHUIZHEN"),
        Map.entry("刘","LIUSIYUAN"));
    private final Map<String, Set<Account>> candidates = new HashMap<>();
    private final Map<String, Long> nameCounts = new HashMap<>();
    PurchaseRankingOwners(List<Account> accounts) {
        for (var user : accounts) {
            add(user.account(), user); add(user.name(), user);
            nameCounts.merge(user.name().trim(), 1L, Long::sum);
            ALIASES.forEach((alias, account) -> { if (account.equalsIgnoreCase(user.account())) add(alias, user); });
        }
    }
    private void add(String token, Account user) {
        if (!token.isBlank()) candidates.computeIfAbsent(token.trim().toUpperCase(Locale.ROOT), k -> new HashSet<>()).add(user);
    }
    String resolve(String owner) {
        var matches = candidates.getOrDefault(owner.trim().toUpperCase(Locale.ROOT), Set.of());
        if (matches.size() != 1) return "";
        var user = matches.iterator().next();
        if (!"purchase".equals(user.role())) return "";
        String name = user.name().trim();
        return nameCounts.getOrDefault(name, 0L) > 1 ? name + "（" + user.account() + "）" : name;
    }
}

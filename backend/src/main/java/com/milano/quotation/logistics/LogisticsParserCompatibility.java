package com.milano.quotation.logistics;

/** Explicitly reviewed producer versions; publication still revalidates rows and billing. */
final class LogisticsParserCompatibility {
    static final String SEPTEMBER_29 = "wanbang-yanwen-express-2026.09.29-v3";
    private static final java.util.Set<String> REVIEWED_VERSIONS=java.util.Set.of(
        SEPTEMBER_29,"yanwen-october-layout-2026.10.04-v1","shared-source-layout-2026.10.04-v2","shared-source-layout-2026.10.09-v3");
    private LogisticsParserCompatibility() {}

    static String blockingReason(String version) {
        // October changes recognize additional source layouts without changing the persisted
        // pricing contract. Keep the original producer version in both source and audit data.
        if (version == null || version.isBlank()) return "草稿缺少解析器版本，不能自动发布，请重新导入原表";
        if (REVIEWED_VERSIONS.contains(version)) return "";
        return "草稿解析器版本尚未验证兼容性，请重新导入原表后发布";
    }
}

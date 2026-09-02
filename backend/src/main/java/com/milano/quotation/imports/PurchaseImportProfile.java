package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;

final class PurchaseImportProfile {
    static final String STANDARD = "standard";
    static final String LEGACY_2026 = "legacy-2026";
    static final String LEGACY_DATA_SOURCE = "legacy_2026";
    static final String LEGACY_SOURCE_LABEL = "2026旧数据";

    private PurchaseImportProfile() {}

    static String normalize(String value) {
        var profile = value == null || value.isBlank() ? STANDARD : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of(STANDARD, LEGACY_2026).contains(profile)) {
            throw AppException.unprocessable("采购导入类型不合法");
        }
        return profile;
    }

    static boolean isLegacy(ImportJob job) {
        return job != null && LEGACY_2026.equals(job.payload.path("importProfile").asText(STANDARD));
    }
}

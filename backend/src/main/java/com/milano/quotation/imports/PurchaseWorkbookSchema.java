package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import java.util.List;

final class PurchaseWorkbookSchema {
    static final List<String> LEGACY_HEADERS = List.of(
            "SKU*", "类别*", "产品图片（嵌入本格）", "实物图（嵌入本格）", "报价人*", "报价日期*", "尺码", "颜色",
            "克重(g)*", "长(cm)*", "宽(cm)*", "高(cm)*", "起订量(件)*", "基准采购单价(CNY/件)*", "阶梯价2起订量", "阶梯价2(CNY/件)",
            "阶梯价3起订量", "阶梯价3(CNY/件)", "1件总运费(CNY)", "10件总运费(CNY)", "100件总运费(CNY)", "是否包邮",
            "含票价(CNY/件)", "票类型", "是否有货*", "备注", "工厂信息", "货源链接1", "货源链接2", "货源链接3", "相似货源", "审核备注");
    static final List<String> INTERNATIONAL_HEADERS = List.of(
            "实物图（嵌入本格）", "报价日期*", "报价人*", "备注", "SKU*", "产品图片（嵌入本格）", "克重(g)*", "尺码", "颜色", "材质",
            "长(cm)*", "宽(cm)*", "高(cm)*", "起订量(件)*", "基准采购单价(CNY/件)*", "阶梯价2起订量", "阶梯价2(CNY/件)",
            "阶梯价3起订量", "阶梯价3(CNY/件)", "1件总运费(CNY)", "10件总运费(CNY)", "100件总运费(CNY)", "是否包邮",
            "含票价(CNY/件)", "票点", "票类型", "类别", "是否有货*", "工厂信息", "审核备注", "货源链接1", "货源链接2", "货源链接3", "相似货源");

    enum Version { LEGACY, INTERNATIONAL }
    private final Version version;
    PurchaseWorkbookSchema(Version version) { this.version = version; }

    static PurchaseWorkbookSchema identify(String[] headers, String sheetName) {
        if (matches(headers, INTERNATIONAL_HEADERS)) return new PurchaseWorkbookSchema(Version.INTERNATIONAL);
        if (matches(headers, LEGACY_HEADERS)) return new PurchaseWorkbookSchema(Version.LEGACY);
        for (int i = 0; i < INTERNATIONAL_HEADERS.size(); i++) {
            var actual = i < headers.length && headers[i] != null ? headers[i].trim() : "";
            if (!INTERNATIONAL_HEADERS.get(i).equals(actual))
                throw AppException.unprocessable("工作表“" + sheetName + "”列头不匹配，" + column(i) + "列应为“" + INTERNATIONAL_HEADERS.get(i) + "”");
        }
        throw AppException.unprocessable("工作表“" + sheetName + "”模板列头不匹配");
    }
    private static boolean matches(String[] actual, List<String> expected) {
        if (actual.length < expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (!expected.get(i).equals(actual[i] == null ? "" : actual[i].trim())) return false;
        return true;
    }
    int width() { return version == Version.INTERNATIONAL ? 34 : 32; }
    int sku() { return version == Version.INTERNATIONAL ? 4 : 0; }
    int productImage() { return version == Version.INTERNATIONAL ? 5 : 2; }
    int physicalImage() { return version == Version.INTERNATIONAL ? 0 : 3; }
    int category() { return version == Version.INTERNATIONAL ? 26 : 1; }
    int owner() { return version == Version.INTERNATIONAL ? 2 : 4; }
    int date() { return version == Version.INTERNATIONAL ? 1 : 5; }
    int size() { return version == Version.INTERNATIONAL ? 7 : 6; }
    int color() { return version == Version.INTERNATIONAL ? 8 : 7; }
    int material() { return version == Version.INTERNATIONAL ? 9 : -1; }
    int weight() { return version == Version.INTERNATIONAL ? 6 : 8; }
    int length() { return version == Version.INTERNATIONAL ? 10 : 9; }
    int widthCm() { return version == Version.INTERNATIONAL ? 11 : 10; }
    int height() { return version == Version.INTERNATIONAL ? 12 : 11; }
    int moq() { return version == Version.INTERNATIONAL ? 13 : 12; }
    int basePrice() { return version == Version.INTERNATIONAL ? 14 : 13; }
    int tier2Qty() { return version == Version.INTERNATIONAL ? 15 : 14; }
    int tier2Price() { return version == Version.INTERNATIONAL ? 16 : 15; }
    int tier3Qty() { return version == Version.INTERNATIONAL ? 17 : 16; }
    int tier3Price() { return version == Version.INTERNATIONAL ? 18 : 17; }
    int freight1() { return version == Version.INTERNATIONAL ? 19 : 18; }
    int freight10() { return version == Version.INTERNATIONAL ? 20 : 19; }
    int freight100() { return version == Version.INTERNATIONAL ? 21 : 20; }
    int freeShipping() { return version == Version.INTERNATIONAL ? 22 : 21; }
    int taxIncludedPrice() { return version == Version.INTERNATIONAL ? 23 : 22; }
    int taxPoint() { return version == Version.INTERNATIONAL ? 24 : -1; }
    int invoiceType() { return version == Version.INTERNATIONAL ? 25 : 23; }
    int stock() { return version == Version.INTERNATIONAL ? 27 : 24; }
    int notes() { return version == Version.INTERNATIONAL ? 3 : 25; }
    int factory() { return version == Version.INTERNATIONAL ? 28 : 26; }
    int auditNotes() { return version == Version.INTERNATIONAL ? 29 : 31; }
    int link1() { return version == Version.INTERNATIONAL ? 30 : 27; }
    int link2() { return version == Version.INTERNATIONAL ? 31 : 28; }
    int link3() { return version == Version.INTERNATIONAL ? 32 : 29; }
    int similar() { return version == Version.INTERNATIONAL ? 33 : 30; }
    boolean international() { return version == Version.INTERNATIONAL; }
    static String column(int index) { var out=new StringBuilder();for(int value=index+1;value>0;value=(value-1)/26)out.insert(0,(char)('A'+(value-1)%26));return out.toString(); }
}

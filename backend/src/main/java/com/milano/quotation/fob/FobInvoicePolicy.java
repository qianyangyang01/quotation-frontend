package com.milano.quotation.fob;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Procurement confirmation is tied to the exact invoice and tier inputs. */
public final class FobInvoicePolicy {
    public static boolean special(String value) {
        var text=Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s\\u200b]+", "");
        return Set.of("专票","含专票","增值税专用发票","special").contains(text)
            || text.matches("(?:专票|增值税专用发票)(?:1|3|6|9|10|13)(?:%|个点)?");
    }
    public static String fingerprint(JsonNode raw) {
        var text=new StringBuilder();
        for(var key:List.of("dataSource","invoiceType","purchasePriceBasis","taxIncludedPriceCny","taxPoint",
                "minOrderQty","purchasePriceCny","tier2MinQty","tier2PriceCny","tier3MinQty","tier3PriceCny",
                "tier4MinQty","tier4PriceCny","tier5MinQty","tier5PriceCny","moqRaw","priceRaw")) {
            var value=raw.path(key);
            var normalized=value.isNumber()?value.decimalValue().stripTrailingZeros().toPlainString():value.isMissingNode()||value.isNull()?"":value.asText();
            text.append(key).append(':').append(normalized.length()).append(':').append(normalized).append(';');
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static boolean confirmed(JsonNode raw) {
        return raw.path("fobInvoiceRatioConfirmed").asBoolean(false)
            && fingerprint(raw).equals(raw.path("fobInvoiceConfirmedFingerprint").asText())
            && !raw.path("invoiceVerificationNote").asText().isBlank();
    }
    public static void maintainConfirmation(ObjectNode value,JsonNode previous,JsonNode submitted) {
        if(submitted.has("fobInvoiceRatioConfirmed")&&!submitted.path("fobInvoiceRatioConfirmed").isBoolean())
            throw AppException.unprocessable("FOB含票阶梯确认必须为勾选值");
        var expected=fingerprint(value);
        boolean explicit=submitted.path("fobInvoiceRatioConfirmed").asBoolean(false)
            && (!submitted.hasNonNull("fobInvoiceConfirmedFingerprint") || submitted.path("fobInvoiceConfirmedFingerprint").asText().equals(expected));
        boolean retained=previous!=null&&confirmed(previous)&&expected.equals(fingerprint(previous))
            && !(submitted.has("fobInvoiceRatioConfirmed")&&!submitted.path("fobInvoiceRatioConfirmed").asBoolean());
        if(explicit||retained){
            var note=value.path("invoiceVerificationNote");
            if(!note.isTextual()||note.asText().isBlank()||note.asText().length()>2000)throw AppException.unprocessable("请填写供应商含票比例适用全部阶梯的核实说明（最多2000字）");
            value.put("fobInvoiceRatioConfirmed",true).put("fobInvoiceConfirmedFingerprint",expected);
        }else{
            value.put("fobInvoiceRatioConfirmed",false);value.remove("fobInvoiceConfirmedFingerprint");
        }
    }
    private FobInvoicePolicy() {}
}

package com.milano.quotation.quote;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
class QuotationDocumentService {
    private static final Set<String> PRIVATE_FIELDS = Set.of(
            "purchasePrice", "purchasePriceCny", "purchaseCost", "cost", "costCny", "costUsd",
            "profit", "profitRate", "margin", "marginRate", "audit", "auditLog", "revisions",
            "internalNote", "supplier", "supplierId", "factoryInfo", "rawTierPrice");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    ObjectNode customerView(QuotationRecordEntity row) {
        var result = sanitize(row.payload).isObject() ? (ObjectNode) sanitize(row.payload) : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        result.put("id", row.id.toString()); result.put("no", row.quoteNo); result.put("status", row.status);
        result.put("createdAt", row.createdAt.toString()); result.put("updatedAt", row.updatedAt.toString());
        return result;
    }

    byte[] pdf(QuotationRecordEntity row) {
        var customer = customerView(row);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var font = loadFont(document);
            var writer = new PdfWriter(document, font);
            writer.heading("MILANO QUOTATION");
            writer.line("Quotation No: " + row.quoteNo, 14);
            writer.line("Created: " + DATE.format(row.createdAt), 10);
            writer.line("Status: " + row.status, 10);
            writer.space(12);
            for (var field : List.of("customerName", "customer", "country", "currency", "validUntil", "note")) {
                var value = customer.path(field);
                if (value.isValueNode() && !value.asText().isBlank()) writer.line(label(field) + ": " + value.asText(), 10);
            }
            writer.space(10);
            var items = firstArray(customer, "items", "products", "quoteItems", "selectedProducts");
            if (items != null && !items.isEmpty()) {
                writer.line("ITEMS", 12);
                int number = 1;
                for (var item : items) {
                    var name = firstText(item, "name", "productName", "category", "sku");
                    var quantity = firstText(item, "quantity", "qty", "dealQuantity");
                    var price = firstText(item, "unitPrice", "quoteUsd", "quoteCny", "price");
                    writer.line(number++ + ". " + value(name, "Item") + value(quantity, "", " | Qty: ") + value(price, "", " | Price: "), 10);
                }
            }
            writer.space(10);
            for (var key : List.of("total", "totalUsd", "totalCny", "actualQuoteUsd", "actualQuoteCny")) {
                var value = customer.path(key);
                if (value.isValueNode() && !value.asText().isBlank()) writer.line(label(key) + ": " + value.asText(), 11);
            }
            writer.finish(); document.save(output); return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("客户版PDF生成失败", exception);
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null || node.isNull()) return tools.jackson.databind.node.NullNode.instance;
        if (node.isObject()) {
            var result = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> {
                if (!PRIVATE_FIELDS.contains(entry.getKey()) && !containsPrivateWord(entry.getKey())) {
                    result.set(entry.getKey(), sanitize(entry.getValue()));
                }
            });
            return result;
        }
        if (node.isArray()) {
            var result = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(sanitize(item))); return result;
        }
        return node.deepCopy();
    }

    private static boolean containsPrivateWord(String key) {
        var normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("purchase") || normalized.contains("profit") || normalized.contains("margin")
                || normalized.contains("audit") || normalized.contains("internal") || normalized.contains("成本") || normalized.contains("利润");
    }

    private static PDFont loadFont(PDDocument document) throws IOException {
        var candidates = List.of(
                Path.of("/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
                Path.of("C:/Windows/Fonts/msyh.ttc"));
        for (var path : candidates) if (Files.isRegularFile(path)) {
            if (path.toString().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                final PDFont[] selected = new PDFont[1];
                try (var collection = new TrueTypeCollection(path.toFile())) {
                    collection.processAllFonts(font -> { if (selected[0] == null) selected[0] = PDType0Font.load(document, font, true); });
                }
                if (selected[0] != null) return selected[0];
            }
            try (var input = Files.newInputStream(path)) { return PDType0Font.load(document, input, true); }
        }
        throw new IOException("没有找到可用的中文字体");
    }

    private static ArrayNode firstArray(ObjectNode node, String... keys) { for (var key : keys) if (node.path(key).isArray()) return (ArrayNode) node.path(key); return null; }
    private static String firstText(JsonNode node, String... keys) { for (var key : keys) if (node.path(key).isValueNode() && !node.path(key).asText().isBlank()) return node.path(key).asText(); return ""; }
    private static String label(String value) { return value.replaceAll("([a-z])([A-Z])", "$1 $2").toUpperCase(Locale.ROOT); }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String value(String value, String fallback, String prefix) { var normalized = value(value, fallback); return normalized.isBlank() ? "" : prefix + normalized; }

    private static final class PdfWriter {
        private final PDDocument document; private final PDFont font; private PDPage page; private PDPageContentStream stream; private float y;
        private PdfWriter(PDDocument document, PDFont font) throws IOException { this.document = document; this.font = font; nextPage(); }
        void heading(String text) throws IOException { line(text, 20); space(8); }
        void line(String text, float size) throws IOException { for (var part : wrap(text, size, 500)) { ensure(55); stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(48, y); stream.showText(part); stream.endText(); y -= size + 7; } }
        void space(float value) { y -= value; }
        void ensure(float required) throws IOException { if (y < required) nextPage(); }
        void finish() throws IOException { if (stream != null) stream.close(); }
        private void nextPage() throws IOException { if (stream != null) stream.close(); page = new PDPage(PDRectangle.A4); document.addPage(page); stream = new PDPageContentStream(document, page); y = 795; }
        private List<String> wrap(String raw, float size, float width) throws IOException {
            var result = new ArrayList<String>(); var line = new StringBuilder();
            for (var codePoint : raw.codePoints().toArray()) {
                var next = line.toString() + new String(Character.toChars(codePoint));
                if (!line.isEmpty() && font.getStringWidth(next) / 1000 * size > width) { result.add(line.toString()); line.setLength(0); }
                line.appendCodePoint(codePoint);
            }
            if (!line.isEmpty()) result.add(line.toString()); return result.isEmpty() ? List.of("") : result;
        }
    }
}

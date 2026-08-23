package com.milano.quotation.quote;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuotationDocumentServiceTest {
    private final QuotationDocumentService documents = new QuotationDocumentService();

    @Test
    void customerViewAndPdfExcludeInternalCostAndProfit() throws Exception {
        var mapper = JsonMapper.builder().build();
        var payload = mapper.createObjectNode();
        payload.put("customerName", "米莱诺客户"); payload.put("totalUsd", "199.00");
        payload.put("purchaseCost", "secret-cost"); payload.put("profitRate", "secret-profit");
        payload.putArray("items").addObject().put("name", "测试商品").put("quantity", 2)
                .put("price", "99.50").put("purchasePriceCny", "secret-item-cost");
        var row = new QuotationRecordEntity(); row.id = UUID.randomUUID(); row.quoteNo = "QT202608220001";
        row.ownerAccount = "EMP001"; row.status = "pending"; row.payload = payload;
        row.createdAt = Instant.parse("2026-08-22T00:00:00Z"); row.updatedAt = row.createdAt;

        var customer = documents.customerView(row);
        assertFalse(customer.has("purchaseCost"));
        assertFalse(customer.has("profitRate"));
        assertFalse(customer.path("items").get(0).has("purchasePriceCny"));

        var bytes = documents.pdf(row);
        assertTrue(bytes.length > 1_000);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "quotation-document-test.pdf"), bytes);
        try (var pdf = Loader.loadPDF(bytes)) {
            var text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("QT202608220001"));
            assertFalse(text.contains("secret-cost"));
            assertFalse(text.contains("secret-profit"));
            assertFalse(text.contains("secret-item-cost"));
        }
    }
}

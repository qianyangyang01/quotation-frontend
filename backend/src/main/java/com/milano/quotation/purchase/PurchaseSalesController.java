package com.milano.quotation.purchase;

import com.milano.quotation.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import com.milano.quotation.common.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Fixed uploaded sales period, joined to current procurement data on every read. */
@RestController
public class PurchaseSalesController {
    private final PurchaseProductRepository products;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode source;
    private final List<String> skus;

    public PurchaseSalesController(PurchaseProductRepository products,
            @Value("${app.purchase-sales.source:}") String sourceLocation) throws IOException {
        this.products = products;
        if (sourceLocation.isBlank()) { source = null; skus = List.of(); return; }
        try (var input = new DefaultResourceLoader().getResource(sourceLocation).getInputStream()) {
            source = mapper.readTree(input);
        }
        var keys = new ArrayList<String>();
        source.path("rows").forEach(row -> keys.add(row.path("sku").asText()));
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank) || keys.size() != new java.util.HashSet<>(keys).size()) {
            throw new IllegalStateException("销量来源为空或存在重复 SKU");
        }
        skus = List.copyOf(keys);
    }

    @GetMapping("/api/v1/purchase-sales")
    @PreAuthorize("hasAuthority('PERM_purchase')")
    @Transactional(readOnly = true)
    public ApiResponse<Result> get() {
        if (source == null) throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SALES_NOT_CONFIGURED", "尚未配置销量数据，请联系管理员");
        var matched = products.salesCatalog(skus).stream().map(mapper::readTree).toList();
        return ApiResponse.ok(new Result(source, matched, Instant.now()));
    }

    public record Result(JsonNode source, List<JsonNode> products, Instant matchedAt) {}
}

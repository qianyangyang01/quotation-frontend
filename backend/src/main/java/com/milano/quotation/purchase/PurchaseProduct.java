package com.milano.quotation.purchase;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="purchase_product")
public class PurchaseProduct {
    @Id public UUID id;
    @Column(nullable=false, unique=true, length=96) public String sku;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false, columnDefinition="jsonb") public JsonNode payload;
    @Column(name="catalog_state", nullable=false, length=24) public String catalogState;
    @Column(name="quote_ready", nullable=false) public boolean quoteReady;
    @Column(name="source_hash", length=64) public String sourceHash;
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    protected PurchaseProduct() {}
    static PurchaseProduct create(String sku, JsonNode payload, String catalogState, boolean quoteReady, String sourceHash) {
        var now = Instant.now(); var row = new PurchaseProduct(); row.id=UUID.randomUUID(); row.sku=sku;
        row.payload=payload; row.catalogState=catalogState; row.quoteReady=quoteReady; row.sourceHash=sourceHash;
        row.createdAt=now; row.updatedAt=now; return row;
    }
}

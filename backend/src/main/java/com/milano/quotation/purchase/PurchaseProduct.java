package com.milano.quotation.purchase;

import com.fasterxml.jackson.databind.JsonNode;
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
    @Version public long version;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="updated_at", nullable=false) public Instant updatedAt;
    protected PurchaseProduct() {}
    static PurchaseProduct create(String sku, JsonNode payload) {
        var now = Instant.now(); var row = new PurchaseProduct(); row.id=UUID.randomUUID(); row.sku=sku;
        row.payload=payload; row.createdAt=now; row.updatedAt=now; return row;
    }
}

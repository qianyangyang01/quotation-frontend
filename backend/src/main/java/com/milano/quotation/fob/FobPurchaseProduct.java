package com.milano.quotation.fob;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import java.time.Instant;

@Entity
@Table(name = "fob_purchase_product")
public class FobPurchaseProduct {
    @Id @Column(length = 96) public String sku;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") public JsonNode payload;
    @Version public Long version;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}

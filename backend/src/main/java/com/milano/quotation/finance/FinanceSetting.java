package com.milano.quotation.finance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name="finance_setting")
public class FinanceSetting {
    @Id @Column(name="setting_key", length=64) public String key;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") public JsonNode payload;
    @Version public long version;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
    protected FinanceSetting() {}
    static FinanceSetting create(String key, JsonNode payload) { var row=new FinanceSetting(); row.key=key; row.payload=payload; row.updatedAt=Instant.now(); return row; }
}

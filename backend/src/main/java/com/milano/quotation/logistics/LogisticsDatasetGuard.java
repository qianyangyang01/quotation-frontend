package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class LogisticsDatasetGuard {
    private final JdbcClient jdbc;
    public LogisticsDatasetGuard(JdbcClient jdbc) { this.jdbc = jdbc; }
    public UUID activeId() {
        return jdbc.sql("select id from logistics_dataset where status='active'").query(UUID.class).single();
    }
    public void writable(UUID datasetId) {
        var status = jdbc.sql("select status from logistics_dataset where id=:id for share")
                .param("id", datasetId).query(String.class).optional().orElseThrow(() -> AppException.notFound("物流库不存在"));
        if (status.equals("archived")) throw AppException.conflict("旧物流库已归档，只能查阅，不能恢复或修改");
    }
    public UUID channel(UUID id) {
        var dataset = jdbc.sql("select dataset_id from logistics_channel where id=:id").param("id",id)
                .query(UUID.class).optional().orElseThrow(() -> AppException.notFound("物流渠道不存在"));
        writable(dataset);
        jdbc.sql("select id from logistics_channel where id=:id for update").param("id",id).query(UUID.class).single();
        return dataset;
    }
    public void activeProvider(UUID id) {
        var dataset = jdbc.sql("select dataset_id from logistics_provider where id=:id").param("id",id)
                .query(UUID.class).optional().orElseThrow(() -> AppException.notFound("物流商不存在"));
        writable(dataset);
        if (!dataset.equals(activeId())) throw AppException.conflict("请从新库准备区操作该物流商");
    }
    public int nextRuleId() { return jdbc.sql("select nextval('logistics_rule_identity')").query(Integer.class).single(); }
    public boolean quoteReady(UUID versionId) { return jdbc.sql("select logistics_version_quote_ready(:id)").param("id",versionId).query(Boolean.class).single(); }
    /** The caller must keep this transaction open through the idempotency response write. */
    public void request(String actor,String operation,String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,120}"))throw AppException.unprocessable("缺少或无效的 Idempotency-Key");
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:actor),hashtext(:request))")
                .param("actor",actor).param("request",operation+":"+key).query(rs->true);
    }
}

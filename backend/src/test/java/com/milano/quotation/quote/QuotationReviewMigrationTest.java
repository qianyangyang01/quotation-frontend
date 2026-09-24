package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationReviewMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void retainsExplicitLegacyResultsWithoutChangingAnyQuotationData() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.execute("create table quotation_record(id uuid primary key,payload jsonb,version bigint,updated_at timestamptz)");
        jdbc.execute("insert into quotation_record values(md5('approved')::uuid,'{\"financeReviewStatus\":\"approved\",\"financeReviewedBy\":\"财务甲\",\"financeReviewedAt\":\"2026-09-20T01:00:00Z\"}',5,now()),(md5('rejected')::uuid,'{\"financeReviewStatus\":\"rejected\",\"financeReviewedAccount\":\"FINANCE\"}',9,now()),(md5('won')::uuid,'{\"status\":\"won\",\"quoteConfirmed\":true}',2,now())");
        var before=jdbc.queryForList("select * from quotation_record order by id");
        try(var resource=getClass().getResourceAsStream("/db/migration/V50__quotation_review_claim.sql")){assertNotNull(resource);jdbc.execute(new String(resource.readAllBytes(),StandardCharsets.UTF_8));}
        assertEquals(before,jdbc.queryForList("select * from quotation_record order by id"));assertEquals(2,jdbc.queryForObject("select count(*) from quotation_review",Integer.class));
        assertEquals("财务甲",jdbc.queryForObject("select state->>'financeReviewedBy' from quotation_review where status='approved'",String.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from quotation_review where id=md5('won')::uuid",Integer.class));
        var reviewsBefore=jdbc.queryForList("select * from quotation_review order by id");
        try(var resource=getClass().getResourceAsStream("/db/migration/V53__quotation_channel_exempt_review.sql")){assertNotNull(resource);jdbc.execute(new String(resource.readAllBytes(),StandardCharsets.UTF_8));}
        assertEquals(before,jdbc.queryForList("select * from quotation_record order by id"));
        assertEquals(reviewsBefore,jdbc.queryForList("select * from quotation_review order by id"));
        jdbc.execute("insert into quotation_review values(md5('won')::uuid,'channel-exempt',null,'{\"financeReviewStatus\":\"channel-exempt\"}',0)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.execute("update quotation_review set status='invalid' where id=md5('won')::uuid"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.execute("update quotation_review set claimant_account='FINANCE' where id=md5('won')::uuid"));
    }
}

package com.milano.quotation.quote;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationReviewNotificationMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void seedsOnlyActiveConclusionsWithoutChangingHistoricalRecordsOrReviews() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("55").load().migrate();
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.createStatement()) {
            s.executeUpdate("""
                insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state)
                select md5(i::text)::uuid,'N-'||i,'EMPLOYEE','won',
                  '{"financeReviewStatus":"approved","financeReviewedBy":"原审核人","financeReviewedAt":"2026-09-28T08:00:00Z","systemQuoteUsd":12.34,"customerQuote":{"price":11}}'::jsonb,
                  7,now(),now(),case when i=4 then 'archived' else 'active' end from generate_series(1,4) i
                """);
            s.executeUpdate("""
                insert into quotation_review(id,status,state,version) values
                (md5('2')::uuid,'rejected','{"financeReviewStatus":"rejected","financeReviewedBy":"财务","financeReviewNote":"请核对成本","history":[{"action":"complete","note":"请核对成本"}]}'::jsonb,5),
                (md5('3')::uuid,'pending','{"financeReviewStatus":"pending"}'::jsonb,6)
                """);
            String quoteBefore,reviewBefore;
            try(var r=s.executeQuery("select md5(string_agg(row_to_json(q)::text,'' order by id)) from quotation_record q")){r.next();quoteBefore=r.getString(1);}
            try(var r=s.executeQuery("select md5(string_agg(row_to_json(q)::text,'' order by id)) from quotation_review q")){r.next();reviewBefore=r.getString(1);}
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
            try(var r=s.executeQuery("select count(*) from quotation_review_notification")){r.next();assertEquals(2,r.getInt(1));}
            try(var r=s.executeQuery("select status,review_version,read_event_id,note from quotation_review_notification where record_id=md5('2')::uuid")){r.next();assertEquals("rejected",r.getString(1));assertEquals(5,r.getLong(2));assertNull(r.getObject(3));assertEquals("请核对成本",r.getString(4));}
            try(var r=s.executeQuery("select md5(string_agg(row_to_json(q)::text,'' order by id)) from quotation_record q")){r.next();assertEquals(quoteBefore,r.getString(1));}
            try(var r=s.executeQuery("select md5(string_agg(row_to_json(q)::text,'' order by id)) from quotation_review q")){r.next();assertEquals(reviewBefore,r.getString(1));}
        }
    }
}

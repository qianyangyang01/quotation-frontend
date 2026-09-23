package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Rebuilds only generated quote projections. Source payloads and approvals are untouched. */
public class V48__logistics_billing_steps extends BaseJavaMigration {
    private byte[] read(String name) {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing rounding migration resource: " + name);
            return input.readAllBytes();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private String sql() {
        var rules = new String(read("/logistics-rules/rounding-notes-20260923.json"), StandardCharsets.UTF_8);
        return new String(read("/logistics-rules/rounding-projection-v48.sql"), StandardCharsets.UTF_8)
                .replace("__REVIEWED_ROUNDING_RULES__", rules.replace("'", "''"));
    }
    @Override public Integer getChecksum() {
        var crc = new CRC32(); crc.update(sql().getBytes(StandardCharsets.UTF_8)); return (int) crc.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) { statement.execute(sql()); }
    }
}

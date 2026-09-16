package db.migration;

import com.milano.quotation.logistics.DocumentedMinimumWeightRepair;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import java.util.zip.CRC32;

/** User-authorized correction of the exact published Tongyou US sensitive B version. */
public class V41__tongyou_sensitive_b_minimum_weight extends BaseJavaMigration {
    public static final String CONFIRMATION = "2026-09-16用户确认：通邮美国专线特敏感B，美国50g起重";
    private byte[] plan() {
        try (var input = getClass().getResourceAsStream("/logistics-repairs/tongyou-sensitive-b-minimum-20260916.json")) {
            if (input == null) throw new IllegalStateException("Missing Tongyou minimum repair plan");
            return input.readAllBytes();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Override public Integer getChecksum() { var crc = new CRC32(); crc.update(plan()); return (int) crc.getValue(); }
    @Override public void migrate(Context context) throws Exception {
        new DocumentedMinimumWeightRepair("migration-v41", CONFIRMATION)
                .apply(context.getConnection(), new ObjectMapper().readTree(plan()));
    }
}

package db.migration;

import com.milano.quotation.logistics.DocumentedMinimumWeightRepair;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import java.util.zip.CRC32;

/** Exact production correction; an installation without this dataset is unchanged. */
public class V40__complete_documented_logistics_minimum_weights extends BaseJavaMigration {
    private byte[] plan() {
        try (var input = getClass().getResourceAsStream("/logistics-repairs/documented-minimum-20260915.json")) {
            if (input == null) throw new IllegalStateException("Missing documented minimum repair plan");
            return input.readAllBytes();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Override public Integer getChecksum() { var crc = new CRC32(); crc.update(plan()); return (int) crc.getValue(); }
    @Override public void migrate(Context context) throws Exception {
        new DocumentedMinimumWeightRepair().apply(context.getConnection(), new ObjectMapper().readTree(plan()));
    }
}

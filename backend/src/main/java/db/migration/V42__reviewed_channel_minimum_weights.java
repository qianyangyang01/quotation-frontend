package db.migration;

import com.milano.quotation.logistics.DocumentedMinimumWeightRepair;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import java.util.zip.CRC32;

/** Immutable, exact-baseline correction of source-documented country minimums. */
public class V42__reviewed_channel_minimum_weights extends BaseJavaMigration {
    private byte[] plan() {
        try(var input=getClass().getResourceAsStream("/logistics-repairs/all-channel-minimum-20260916.json")) {
            if(input==null)throw new IllegalStateException("Missing reviewed minimum plan");
            return input.readAllBytes();
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new CRC32();crc.update(plan());return (int)crc.getValue();}
    @Override public void migrate(Context context)throws Exception {
        DocumentedMinimumWeightRepair.reviewedSources("migration-v42").apply(context.getConnection(),new ObjectMapper().readTree(plan()));
    }
}

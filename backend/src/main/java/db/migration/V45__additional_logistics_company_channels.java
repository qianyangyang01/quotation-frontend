package db.migration;

import com.milano.quotation.logistics.AdditionalCompanyChannels;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import java.util.zip.CRC32;

/** User-approved 15-channel directory addition; published tariffs remain unchanged. */
public class V45__additional_logistics_company_channels extends BaseJavaMigration {
    private byte[] plan() {
        try(var input=getClass().getResourceAsStream("/logistics-repairs/additional-company-channels-20260921.json")) {
            if(input==null)throw new IllegalStateException("Missing additional company channels");
            return input.readAllBytes();
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new CRC32();crc.update(plan());return (int)crc.getValue();}
    @Override public void migrate(Context context)throws Exception {
        AdditionalCompanyChannels.apply(context.getConnection(),new ObjectMapper().readTree(plan()));
    }
}

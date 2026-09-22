package db.migration;

import com.milano.quotation.logistics.AdditionalCompanyChannels;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.databind.ObjectMapper;
import java.util.zip.CRC32;

/** Adds the three requested directory entries; never publishes tariffs or grants finance access. */
public class V46__sunyou_company_channels extends BaseJavaMigration {
    private byte[] plan() {
        try(var input=getClass().getResourceAsStream("/logistics-repairs/sunyou-company-channels-20260922.json")) {
            if(input==null)throw new IllegalStateException("Missing Sunyou channel directory");
            return input.readAllBytes();
        }catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    @Override public Integer getChecksum(){var crc=new CRC32();crc.update(plan());return (int)crc.getValue();}
    @Override public void migrate(Context context)throws Exception {
        AdditionalCompanyChannels.apply(context.getConnection(),new ObjectMapper().readTree(plan()),"migration-v46-sunyou-channels");
    }
}

package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;

/** Optional read-only audit of a production snapshot in an isolated local process. */
class AllChannelMinimumAuditTest {
    @Test @EnabledIfEnvironmentVariable(named="QUOTATION_ALL_MINIMUM_AUDIT",matches=".+")
    void resolveEveryStoredRowWithoutChangingTheSnapshot() throws Exception {
        var mapper=new ObjectMapper();var base=Path.of(System.getenv("QUOTATION_ALL_MINIMUM_AUDIT"));
        var snapshot=mapper.readTree(Files.readString(base.resolve("before.json")).replace("\uFEFF",""));
        var out=mapper.createArrayNode();int total=0;
        for(var c:snapshot.path("channels")) {
            int index=0;
            for(var row:c.path("version").path("rows")) {
                var notes=row.path("notes").asText()+"\n"+row.path("sourceNotes").asText()+"\n"+c.path("version").path("sourceNotes").asText();
                var resolution=LogisticsMinimumWeight.fromNotes(notes,row.path("countryCode").asText());
                var item=out.addObject().put("channelId",c.path("id").asText()).put("index",index++)
                        .put("country",row.path("countryCode").asText()).put("name",c.path("channel").path("name").asText())
                        .put("current",LogisticsBillingEngine.minimum(row)).put("conflict",resolution.conflict()).put("evidence",resolution.evidence());
                if(resolution.kg()!=null)item.put("resolved",resolution.kg());
                total++;
            }
        }
        Files.writeString(base.resolve("resolved-notes.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        System.out.println("ALL_MINIMUM_AUDIT rows="+total+" channels="+snapshot.path("channels").size());
    }
}

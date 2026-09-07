package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsCurrentUploadTest {
    @Test @EnabledIfSystemProperty(named="logistics.currentCorpus",matches=".+")
    void parsesCurrentUploadWithinBoundedHeap()throws Exception {
        var mapper=new ObjectMapper();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        var output=Path.of("target/current-corpus");Files.createDirectories(output);
        var summary=mapper.createArrayNode();
        try(var paths=Files.list(Path.of(System.getProperty("logistics.currentCorpus")))) {
            for(var path:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")).sorted().toList()) {
                long start=System.nanoTime();var parsed=parser.parse(Files.readAllBytes(path),path.getFileName().toString());
                long elapsed=(System.nanoTime()-start)/1_000_000;
                Files.writeString(output.resolve(path.getFileName()+".json"),mapper.writeValueAsString(parsed));
                var item=summary.addObject().put("file",path.getFileName().toString()).put("ms",elapsed).put("sheets",parsed.path("sheets").size()).put("channels",parsed.path("channels").size());
                item.set("pending",mapper.valueToTree(parsed.path("channels").valueStream().filter(c->c.path("templateStatus").asText().equals("adapter-required")).map(c->c.path("channelName").asText()).toList()));
                Files.writeString(output.resolve("summary.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
                System.out.println("CORPUS "+item);
                assertTrue(elapsed<120_000,path+" exceeded per-file budget");
            }
        }
        assertEquals(16,summary.size());
    }
}

package com.milano.quotation.logistics;

import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanyChannelCorpusTest {
    @Test void buildsDirectoryFromRealBaselineAndReconcilesScopedPrices() throws Exception {
        var folder=System.getProperty("company.corpus","");Assumptions.assumeFalse(folder.isBlank());
        var mapper=JsonMapper.builder().build();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        var entries=new LinkedHashMap<String,ObjectNode>();var parsedFiles=new LinkedHashMap<Path,ObjectNode>();
        var started=System.nanoTime();
        try(var paths=Files.list(Path.of(folder))){for(var file:paths.filter(p->p.getFileName().toString().matches("(?i)^(?!~\\$).*\\.xlsx?$")).sorted().toList()){
            var parsed=parser.parse(Files.readAllBytes(file),file.getFileName().toString());parsedFiles.put(file,parsed);
            for(var c:parsed.path("channels")){
                String key=LogisticsSourceParser.identity(c);var entry=entries.computeIfAbsent(key,k->{
                    var e=mapper.createObjectNode().put("id",UUID.nameUUIDFromBytes(("company-channel:"+key).getBytes(StandardCharsets.UTF_8)).toString())
                            .put("providerName",c.path("providerName").asText()).put("channelName",c.path("channelName").asText())
                            .put("logisticsAttribute",c.path("logisticsAttribute").asText()).put("enabled",true);
                    e.putArray("aliases");var aliases=e.putArray("providerAliases");if(c.path("providerName").asText().equals("递四方"))aliases.add("4PX");
                    e.putArray("productCodes");e.putArray("sources");return e;
                });
                var codes=new TreeSet<String>();for(var code:entry.path("productCodes"))codes.add(code.asText());
                var cells=new LinkedHashSet<String>();
                for(var row:c.path("rows")){
                    var code=row.path("sourceProductCode").asText();if(code.matches("[A-Za-z][A-Za-z0-9_-]{1,60}"))codes.add(code);
                    var cell=row.path("sourceSheet").asText()+":"+row.path("sourceRow").asInt();if(cells.add(cell))
                        entry.withArray("sources").addObject().put("file",file.getFileName().toString()).put("sheet",row.path("sourceSheet").asText()).put("row",row.path("sourceRow").asInt());
                }
                var list=entry.putArray("productCodes");codes.forEach(list::add);
            }
        }}
        assertEquals(11,parsedFiles.size());assertEquals(88,entries.size());
        // Shared source codes are evidence, not unique selectors for a company channel.
        var owners=new HashMap<String,Set<String>>();for(var e:entries.values())for(var code:e.path("productCodes"))owners.computeIfAbsent(e.path("providerName").asText()+"|"+CompanyChannelScope.normalize(code.asText()),k->new HashSet<>()).add(e.path("id").asText());
        for(var e:entries.values()){var raw=e.path("productCodes").deepCopy();var unique=e.putArray("productCodes");var shared=e.putArray("sharedSourceCodes");for(var code:raw){if(owners.get(e.path("providerName").asText()+"|"+CompanyChannelScope.normalize(code.asText())).size()==1)unique.add(code);else shared.add(code);}}
        var snapshot=mapper.createObjectNode().put("revision",0).put("enabled",true).put("baseline","8.27渠道价格");var list=snapshot.putArray("entries");entries.values().forEach(list::add);
        CompanyChannelService.validate(list);
        var scope=new CompanyChannelScope(snapshot);long baselineMs=(System.nanoTime()-started)/1_000_000;started=System.nanoTime();
        for(var f:parsedFiles.entrySet()){
            var scoped=parser.parse(Files.readAllBytes(f.getKey()),f.getKey().getFileName().toString(),scope);
            assertEquals(0,scoped.path("ambiguousChannels").asInt(),f.getKey().toString());
            assertEquals(f.getValue().path("channels").size(),scoped.path("channels").size(),f.getKey().toString());
            for(var c:scoped.path("channels")){
                var original=java.util.stream.StreamSupport.stream(f.getValue().path("channels").spliterator(),false).filter(o->o.path("channelName").equals(c.path("channelName"))).findFirst().orElseThrow();
                assertEquals(original.path("contentHash"),c.path("contentHash"),f.getKey()+" "+c.path("channelName"));
            }
        }
        var destination=Path.of("target/company-channel-baseline.json");Files.createDirectories(destination.getParent());Files.writeString(destination,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        System.out.println("Company baseline: 11 files, 88 channels; fullMs="+baselineMs+", scopedMs="+(System.nanoTime()-started)/1_000_000);
    }
}

package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsPriceWorkflowCorpusTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    @Test @EnabledIfSystemProperty(named="logistics.workflowRoot",matches=".+")
    void parsesBothGenerationsWithTraceablePrices() throws Exception {
        var report=mapper.createArrayNode();
        for(var directory:List.of("8.27渠道价格","最新物流")) {
            try(var paths=Files.list(Path.of(System.getProperty("logistics.workflowRoot"),directory))) {
                for(var file:paths.filter(p->p.toString().matches("(?i).*\\.xlsx?$")&&!p.getFileName().toString().startsWith("~$")).sorted().toList()) {
                    var original=Files.readAllBytes(file);var parsed=parser.parse(original,file.getFileName().toString());
                    assertArrayEquals(original,Files.readAllBytes(file),"Source workbook must remain unchanged");
                    var out=report.addObject().put("directory",directory).put("file",file.getFileName().toString());
                    var sheets=out.putArray("sheets");for(var sheet:parsed.path("sheets")) {
                        var compact=(ObjectNode)sheet.deepCopy();if(!directory.equals("最新物流"))compact.remove("sourceCells");sheets.add(compact);
                    }
                    var channels=out.putArray("channels");
                    for(var channel:parsed.path("channels")) {
                        var compact=(ObjectNode)channel.deepCopy();compact.remove("sourceCells");compact.remove("sourceNotes");
                        for(var row:compact.path("rows")) {((ObjectNode)row).remove("notes");((ObjectNode)row).remove("sourceCells");}
                        compact.set("engineReasons",mapper.valueToTree(new LogisticsBillingEngine(mapper).unsupported(compact.path("rows"))));
                        assertTrue(compact.path("engineReasons").isEmpty(),file+" / "+channel.path("channelName")+compact.path("engineReasons"));
                        for(var row:channel.path("rows"))assertEquals("per-kg",row.path("pricingModel").asText());
                        if(channel.path("errors").asInt()>0) {
                            assertEquals("最新物流",directory);assertEquals("敏货电商专递",channel.path("channelName").asText());
                            assertEquals(1,channel.path("errors").asInt());assertTrue(channel.path("issues").toString().contains("重叠档位"));
                        } else for(var row:channel.path("rows")) {
                            var weight=row.path("weightFromKg").decimalValue().add(row.path("weightToKg").decimalValue()).divide(java.math.BigDecimal.valueOf(2));
                            var zone=row.path("zoneName").asText();zone=zone.isBlank()?"全国统一":LogisticsBillingEngine.splitZones(zone).iterator().next();
                            var input=mapper.createObjectNode().put("weightKg",weight).put("country",row.path("countryCode").asText()).put("zoneName",zone);
                            var result=assertDoesNotThrow(()->new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),input),file+" / "+channel.path("channelName")+" source row "+row.path("sourceRow")+" input "+input);
                            var expected=weight.multiply(row.path("pricePerKg").decimalValue()).add(row.path("registrationFee").decimalValue()).setScale(2,java.math.RoundingMode.HALF_UP);
                            assertEquals(0,expected.compareTo(result.path("total").decimalValue()),file+" / "+row.path("sourceSheet")+" row "+row.path("sourceRow"));
                        }
                        channels.add(compact);
                    }
                    System.out.println("WORKFLOW "+directory+" / "+file.getFileName()+" channels="+channels.size());
                    assertFalse(parsed.path("sheets").isEmpty());
                }
            }
        }
        var target=Path.of("target/logistics-workflow.json");Files.createDirectories(target.getParent());
        Files.writeString(target,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertEquals(15,report.size());
        int baseline=0,missing=0;
        for(var book:report)if(book.path("directory").asText().equals("8.27渠道价格")&&!book.path("file").asText().equals("4px价格.xlsx"))for(var channel:book.path("channels")) {
            baseline++;if(!channel.path("etaReady").asBoolean())missing++;
            assertEquals(0,channel.path("errors").asInt());
            assertTrue(channel.path("quoteReady").asBoolean(),channel.path("channelName").asText());
        }
        assertEquals(88,baseline);assertEquals(7,missing);
    }
}

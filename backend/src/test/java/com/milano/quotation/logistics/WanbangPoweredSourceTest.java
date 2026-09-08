package com.milano.quotation.logistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WanbangPoweredSourceTest {
 @Test @EnabledIfSystemProperty(named="wanbang.source",matches=".+")
 void reconcilesPoweredProductWithoutImportingOtherProducts() throws Exception {
  var mapper=new ObjectMapper();var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
  var scope=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/wanbang-powered-company-channel.json")));
  CompanyChannelService.validate(scope.path("entries"));scope.put("enabled",true).put("revision",123);
  var result=parser.parse(Files.readAllBytes(Path.of(System.getProperty("wanbang.source"))),"万邦速达专线挂号含电.xlsx",new CompanyChannelScope(scope));
  Files.createDirectories(Path.of("target/wanbang"));Files.writeString(Path.of("target/wanbang/parsed.json"),result.toPrettyString());
  assertEquals(1,result.path("channels").size(),result.toPrettyString());
  var channel=result.path("channels").get(0);
  assertEquals("万邦速达专线挂号含电",channel.path("channelName").asText());
  assertEquals("带电",channel.path("logisticsAttribute").asText());
  assertEquals(131,channel.path("rows").size());
  assertTrue(channel.path("sourceNotes").asText().contains("100RMB"));
  assertTrue(channel.path("rows").get(0).path("reviewWarning").asText().contains("表尾计费"));
  int us=0;for(var row:channel.path("rows")) {
   assertTrue(java.util.Set.of("EUSLR","USECSLR").contains(row.path("sourceProductCode").asText()));
   assertTrue(row.path("sourceRow").asInt()<=162);
   if(row.path("countryCode").asText().equals("US")){
    assertEquals(new double[]{97,97,92,92,88,88,79}[us],row.path("pricePerKg").asDouble());
    assertEquals(new double[]{23,21,19,19,19,12,12}[us++],row.path("registrationFee").asDouble());
    assertEquals(9,row.path("etaMinDays").asInt());assertEquals(12,row.path("etaMaxDays").asInt());
    assertEquals(0.05,row.path("minChargeWeightKg").asDouble());
   }
  }
  assertEquals(7,us);
 }
}

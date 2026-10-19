package com.milano.quotation.logistics;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class TongyouEpacketTest {
 final ObjectMapper m=new ObjectMapper();
 tools.jackson.databind.JsonNode parse(byte[] bytes) {
  var scope=m.createObjectNode().put("enabled",true);scope.putArray("entries").addObject().put("id","951e7845-fc2d-4c22-98ec-8455d689da89").put("providerName","通邮").put("channelName","TY-E邮宝").put("logisticsAttribute","带电").put("enabled",true);
  return new LogisticsSourceParser(m,new LogisticsWorkbookService(m)).parse(bytes,"通邮.xlsx",new CompanyChannelScope(scope)).path("channels").get(0);
 }
 byte[] workbook(boolean limit,boolean postal,double rate) throws Exception {
  try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
   var s=w.createSheet("新价页");s.createRow(0).createCell(0).setCellValue("TY-E邮宝");s.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,5));
   s.createRow(2).createCell(2).setCellValue("路向");var h=s.createRow(4);h.createCell(3).setCellValue("运费（元/kg）");h.createCell(4).setCellValue("处理费（元/票）");
   var r=s.createRow(5);r.createCell(2).setCellValue("西班牙（偏远邮编）");r.createCell(3).setCellValue(rate);r.createCell(4).setCellValue(20);
   if(limit)s.createRow(7).createCell(0).setCellValue("1、单票限重2公斤；");
   if(postal)s.createRow(9).createCell(0).setCellValue("3、仅支持51/35/38/52/07开头的邮编；");
   w.write(out);return out.toByteArray();
  }
 }
 @Test void readsMovedColumnsAndTitleAndRequiresExplicitPostalZone() throws Exception {
  var c=parse(workbook(true,true,108));assertEquals(0,c.path("errors").asInt(),c.toString());assertEquals(5,c.path("rows").size());
  var rows=c.path("rows");var e=new LogisticsBillingEngine(m);
  for(var row:rows) {assertEquals(2,row.path("weightToKg").asInt());assertEquals(108,row.path("pricePerKg").asInt());assertEquals(20,row.path("registrationFee").asInt());assertEquals(6,row.path("sourceRow").asInt());
   var in=m.createObjectNode().put("country","ES").put("weightKg",0.5).put("zoneName",row.path("zoneName").asText());assertEquals(74,e.calculate(rows,in).path("total").asDouble());
   in.put("weightKg",2);assertEquals(236,e.calculate(rows,in).path("total").asDouble());in.put("weightKg",2.001);assertThrows(RuntimeException.class,()->e.calculate(rows,in));
  }
  assertThrows(RuntimeException.class,()->e.calculate(rows,m.createObjectNode().put("country","ES").put("weightKg",0.5)));
  assertThrows(RuntimeException.class,()->e.calculate(rows,m.createObjectNode().put("country","ES").put("weightKg",0.5).put("zoneName","邮编28开头")));
  assertThrows(RuntimeException.class,()->e.calculate(rows,m.createObjectNode().put("country","FR").put("weightKg",0.5)));
  assertTrue(rows.valueStream().anyMatch(r->r.path("zonePostalPrefix").asText().equals("07")));
 }
 @Test void requiresLimitsAndPostalEvidenceAndReadsChangedPrice() throws Exception {
  assertTrue(parse(workbook(false,true,108)).path("errors").asInt()>0);assertTrue(parse(workbook(true,false,108)).path("errors").asInt()>0);
  assertEquals(112,parse(workbook(true,true,112)).path("rows").get(0).path("pricePerKg").asDouble());
 }
 @Test void suppliedSource() throws Exception {
  var path=System.getProperty("tongyou.epacket.source","");org.junit.jupiter.api.Assumptions.assumeFalse(path.isBlank());
  var c=parse(Files.readAllBytes(Path.of(path)));assertEquals(0,c.path("errors").asInt(),c.toString());assertEquals(5,c.path("rows").size());
  Files.createDirectories(Path.of("target/tongyou-epacket"));Files.writeString(Path.of("target/tongyou-epacket/parsed.json"),c.toString());
 }
}

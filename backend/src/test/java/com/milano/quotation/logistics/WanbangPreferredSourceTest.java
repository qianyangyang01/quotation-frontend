package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WanbangPreferredSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    JsonNode source() throws Exception {
        try(var in=getClass().getResourceAsStream("/wanbang-preferred-20261026.json")){return mapper.readTree(in);}
    }
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/wanbang-preferred-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));
        return new CompanyChannelScope(config.put("enabled",true).put("revision",1));
    }
    byte[] fixture(boolean moved,String defect,int increase) throws Exception {
        try(var book=new XSSFWorkbook()) {
            var products=new ArrayList<JsonNode>();source().path("channels").forEach(products::add);
            if(moved)Collections.reverse(products);
            for(var product:products) {
                var s=book.createSheet(moved?"新报价页"+book.getNumberOfSheets():product.path("channelName").asText());
                int h=moved?40:4;
                LogisticsSourceParserTest.row(s,h-3,product.path("title").asText());
                var labels=new ArrayList<>(List.of("国家","产品名称","产品代码","参考时效（工作日）","重量限制(KG)","尺寸限制","重量段","公斤重(RMB/KG)","操作费(RMB/PCS)"));
                if(moved)Collections.rotate(labels,4);
                var header=s.createRow(h);
                for(int c=0;c<labels.size();c++)header.createCell(c).setCellValue(
                    defect.equals("weight-column")&&labels.get(c).equals("重量段")?"未知列":
                    defect.equals("fee-column")&&labels.get(c).contains("操作费")?"未知列":labels.get(c));
                String country="";int start=h+1;int r=h;
                for(var expected:product.path("rows")) {
                    r++;
                    boolean newCountry=!country.equals(expected.path("country").asText());
                    if(newCountry&&r>start) {
                        merge(s,labels,start,r-1);start=r;
                    }
                    country=expected.path("country").asText();
                    var data=List.of(country,defect.equals("title-only")?"":expected.path("product").asText(),
                        defect.equals("code")?"OTHER-P":expected.path("code").asText(),expected.path("eta").asText(),expected.path("limit").asText(),expected.path("size").asText(),
                        defect.equals("weight-row")&&r==h+2?"":expected.path("weight").asText(),expected.path("pricePerKg").asDouble()+increase,expected.path("registrationFee").asDouble());
                    var keys=List.of("国家","产品名称","产品代码","参考时效（工作日）","重量限制(KG)","尺寸限制","重量段","公斤重(RMB/KG)","操作费(RMB/PCS)");
                    var row=s.createRow(r);
                    for(int c=0;c<labels.size();c++) {
                        int index=keys.indexOf(labels.get(c));if(!newCountry&&index<6)continue;
                        if(defect.equals("fee-cell")&&index==8&&r==h+2)continue;
                        if(defect.equals("price-cell")&&index==7&&r==h+2)continue;
                        var value=data.get(index);var cell=row.createCell(c);
                        if(value instanceof Number number)cell.setCellValue(number.doubleValue());else cell.setCellValue(value.toString());
                    }
                }
                merge(s,labels,start,r);
                LogisticsSourceParserTest.row(s,r+2,"注意事项：");
                LogisticsSourceParserTest.row(s,r+3,product.path("taxNote").asText());
                LogisticsSourceParserTest.row(s,r+4,"退回海外仓库收费如下：");
                LogisticsSourceParserTest.row(s,r+5,"产品名称","产品代码","公斤重（RMB/KG)","操作费(RMB/票）");
                LogisticsSourceParserTest.row(s,r+6,"德国优选专线普货","EUSLPH-P",999,999);
                LogisticsSourceParserTest.row(s,r+7,"偏远邮编地区附加费如下：");
                LogisticsSourceParserTest.row(s,r+8,"产品名称","产品代码","重量段（KG）","操作费(RMB/票）");
                LogisticsSourceParserTest.row(s,r+9,"葡萄牙优选专线普货","EUSLPH-P","0-30KG",999);
            }
            var reference=book.createSheet("葡萄牙偏远邮编");
            LogisticsSourceParserTest.row(reference,1048575,"1000");
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    void merge(org.apache.poi.ss.usermodel.Sheet sheet,List<String> labels,int start,int end) {
        if(end<=start)return;
        for(int c=0;c<labels.size();c++)if(!Set.of("重量段","公斤重(RMB/KG)","操作费(RMB/PCS)").contains(labels.get(c)))
            sheet.addMergedRegion(new CellRangeAddress(start,end,c,c));
    }
    ObjectNode reconcile(ObjectNode result,int increase) throws Exception {
        assertEquals(3,result.path("channels").size(),result.toPrettyString());
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));var cases=evidence.putArray("cases");var rejected=evidence.putArray("rejected");
        for(var expectedChannel:source().path("channels")) {
            var name=expectedChannel.path("channelName").asText();
            var channel=result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(name)).findFirst().orElseThrow();
            assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());
            assertEquals(0,channel.path("errors").asInt(),channel.toPrettyString());
            assertEquals(27,channel.path("rows").size());
            assertEquals(name.endsWith("普货")?"普货":name.endsWith("含电")?"普货、带电":"普货、带电、化妆品",channel.path("logisticsAttribute").asText());
            for(var expected:expectedChannel.path("rows")) {
                var country=expected.path("countryCode").asText();var range=expected.path("weight").asText().toUpperCase(Locale.ROOT).replace("KG","").split("-");
                double from=Double.parseDouble(range[0]),to=Double.parseDouble(range[1]);
                var row=channel.path("rows").valueStream().filter(v->v.path("countryCode").asText().equals(country)&&v.path("weightFromKg").asDouble()==from).findFirst().orElseThrow();
                assertEquals(to,row.path("weightToKg").asDouble());
                assertEquals(expected.path("pricePerKg").asDouble()+increase,row.path("pricePerKg").asDouble());
                assertEquals(expected.path("registrationFee").asDouble(),row.path("registrationFee").asDouble());
                assertEquals(expected.path("code").asText(),row.path("sourceProductCode").asText());
                var eta=expected.path("eta").asText().split("--");
                assertEquals(Integer.parseInt(eta[0]),row.path("etaMinDays").asInt());assertEquals(Integer.parseInt(eta[1]),row.path("etaMaxDays").asInt());
                for(double weight:List.of(from==0?.001:from,(from+to)/2,to)) {
                    double total=BigDecimal.valueOf(expected.path("pricePerKg").asDouble()+increase).multiply(BigDecimal.valueOf(weight)).add(expected.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP).doubleValue();
                    assertEquals(total,engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",weight)).path("total").asDouble(),name+country+weight);
                    cases.addObject().put("channel",name).put("country",country).put("weightKg",weight).put("total",total);
                }
                if(from>0) {
                    double gap=from-.0005;
                    assertThrows(AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",gap)));
                    rejected.addObject().put("channel",name).put("country",country).put("weightKg",gap);
                }
            }
            for(String country:List.of("DE","FR","IT","ES","PT","NL")) {
                double overweight=country.equals("IT")?5.001:30.001;
                assertThrows(AppException.class,()->engine.calculate(channel.path("rows"),mapper.createObjectNode().put("country",country).put("weightKg",overweight)));
                rejected.addObject().put("channel",name).put("country",country).put("weightKg",overweight);
            }
        }
        return evidence;
    }
    @Test void preservesAllSourcePricesAcrossRenamesReorderedColumnsAndMergedCountries() throws Exception {
        var first=parser.parse(fixture(false,"",0),"万邦报价.xlsx",directory());reconcile(first,0);
        var changed=parser.parse(fixture(true,"",3),"新版文件.xlsx",directory());reconcile(changed,3);
        for(var current:changed.path("channels")) {
            var prior=first.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(current.path("companyChannelId"))).findFirst().orElseThrow();
            assertNotEquals(prior.path("contentHash"),current.path("contentHash"));
        }
        reconcile(parser.parse(fixture(true,"title-only",0),"新版文件.xlsx",directory()),0);
        Files.createDirectories(Path.of("target/wanbang-preferred"));
        Files.writeString(Path.of("target/wanbang-preferred/billing-cases.json"),reconcile(first,0).toPrettyString());
    }
    @Test void blocksMissingPricesRangesFeesAndWrongProductCodes() throws Exception {
        for(String defect:List.of("code","weight-column","weight-row","fee-column","fee-cell","price-cell")) {
            var result=parser.parse(fixture(true,defect,0),"万邦更新.xlsx",directory());
            assertEquals(3,result.path("channels").size(),defect+result.toPrettyString());
            for(var channel:result.path("channels"))assertFalse(channel.path("quoteReady").asBoolean(),defect+channel.toPrettyString());
        }
    }
    @Test @EnabledIfSystemProperty(named="wanbang.preferred.source",matches=".+")
    void reconcilesUnmodifiedOriginalWorkbookIncludingLargeReferenceSheets() throws Exception {
        var path=Path.of(System.getProperty("wanbang.preferred.source"));
        var result=parser.parse(Files.readAllBytes(path),path.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/wanbang-preferred"));
        Files.writeString(Path.of("target/wanbang-preferred/parsed.json"),result.toPrettyString());
        var evidence=reconcile(result,0);
        Files.writeString(Path.of("target/wanbang-preferred/original-billing-cases.json"),evidence.toPrettyString());
    }
}

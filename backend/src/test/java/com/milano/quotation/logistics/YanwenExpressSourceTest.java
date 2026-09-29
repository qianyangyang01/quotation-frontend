package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YanwenExpressSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    CompanyChannelScope directory() throws Exception {
        var config=(ObjectNode)mapper.readTree(Files.readString(Path.of("../scripts/logistics/yanwen-express-company-channels.json")));
        CompanyChannelService.validate(config.path("entries"));return new CompanyChannelScope(config.put("enabled",true).put("revision",12));
    }
    byte[] fixture(String defect) throws Exception {
        try(var b=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            for(var code:List.of("557","440")) {
                var s=b.createSheet("重新排序的页面"+code);
                LogisticsSourceParserTest.row(s,33,"燕文专线快递-"+(code.equals("440")?"普货":"特货"));
                LogisticsSourceParserTest.row(s,34,"产品号",defect.equals("code")?"999":code);
                LogisticsSourceParserTest.row(s,36,"处理费(元/件)","最小计费重量(KG)","国家","重量段(KG)","公斤运费(元/KG)","CountryCode");
                if(defect.equals("fee"))s.getRow(36).getCell(0).setCellValue("未知费用");
                LogisticsSourceParserTest.row(s,37,50,defect.equals("minimum")?"":"0.05","以色列",defect.equals("weight")?"":"0.001 - 5",code.equals("440")?57:67,"IL");
                LogisticsSourceParserTest.row(s,38,60,"0.05","以色列","5.001 - 20",code.equals("440")?57:67,"IL");
            }
            return LogisticsSourceParserTest.bytes(b);
        }
    }
    @Test void scansMovedColumnsAndTitlesAndBlocksMissingCriticalFields() throws Exception {
        var good=parser.parse(fixture(""),"燕文更新.xlsx",directory());
        assertEquals(2,good.path("channels").size());
        for(var c:good.path("channels")){assertTrue(c.path("quoteReady").asBoolean(),c.toPrettyString());assertEquals(2,c.path("rows").size());}
        for(var defect:List.of("code","fee","minimum","weight")) {
            var result=parser.parse(fixture(defect),"燕文更新.xlsx",directory());
            assertEquals(2,result.path("channels").size(),result.toPrettyString());
            for(var c:result.path("channels"))assertFalse(c.path("quoteReady").asBoolean(),defect+c.toPrettyString());
        }
    }
    @Test @EnabledIfSystemProperty(named="yanwen.express.source",matches=".+")
    void reconcilesOriginalPricesMinimaZonesAndIsraelDelivery() throws Exception {
        var source=Path.of(System.getProperty("yanwen.express.source"));
        var result=parser.parse(Files.readAllBytes(source),source.getFileName().toString(),directory());
        Files.createDirectories(Path.of("target/yanwen-express"));
        Files.writeString(Path.of("target/yanwen-express/parsed.json"),result.toPrettyString());
        assertEquals(2,result.path("channels").size(),result.toPrettyString());
        var evidence=mapper.createObjectNode();evidence.set("channels",result.path("channels"));var cases=evidence.putArray("cases");
        try(var reader=new LogisticsSheetReader(Files.readAllBytes(source),source.getFileName().toString(),name->!name.equals("燕文专线快递-普货")&&!name.equals("燕文专线快递-特货"))) {
            var format=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
            while(reader.hasNext()) {
                var sheet=reader.next();if(!Set.of("燕文专线快递-普货","燕文专线快递-特货").contains(sheet.getSheetName()))continue;
                boolean normal=sheet.getSheetName().endsWith("普货");
                var channel=result.path("channels").valueStream().filter(c->c.path("channelName").asText().equals(sheet.getSheetName())).findFirst().orElseThrow();
                assertTrue(channel.path("quoteReady").asBoolean(),channel.toPrettyString());assertEquals(0,channel.path("errors").asInt(),channel.toPrettyString());
                assertEquals(normal?75:60,channel.path("rows").size());
                var sourceRows=new HashSet<Integer>();
                for(var original:sheet)if(original.getRowNum()>=4&&original.getRowNum()<(normal?82:67)
                    &&format.formatCellValue(original.getCell(5)).matches("[0-9.]+\\s*-\\s*[0-9.]+"))sourceRows.add(original.getRowNum()+1);
                assertEquals(sourceRows.size(),channel.path("rows").size());
                for(var row:channel.path("rows")) {
                    assertTrue(sourceRows.remove(row.path("sourceRow").asInt()));
                    var original=sheet.getRow(row.path("sourceRow").asInt()-1);
                    var rate=new BigDecimal(format.formatCellValue(original.getCell(3)));var fee=new BigDecimal(format.formatCellValue(original.getCell(4)));
                    var minimum=new BigDecimal(format.formatCellValue(original.getCell(6)));
                    var bounds=format.formatCellValue(original.getCell(5)).split("\\s*-\\s*");
                    var lo=new BigDecimal(bounds[0]);var hi=new BigDecimal(bounds[1]);
                    assertEquals(normal?"440":"557",row.path("sourceProductCode").asText());
                    assertEquals(rate.doubleValue(),row.path("pricePerKg").asDouble());assertEquals(fee.doubleValue(),row.path("registrationFee").asDouble());
                    assertEquals(minimum.doubleValue(),row.path("minChargeWeightKg").asDouble());
                    assertEquals(lo.doubleValue(),row.path("weightFromKg").asDouble());assertEquals(hi.doubleValue(),row.path("weightToKg").asDouble());
                    assertNotEquals("JP",row.path("countryCode").asText());
                    for(var weight:List.of(lo.max(minimum),lo.add(hi).divide(BigDecimal.valueOf(2)),hi)) {
                        var computed=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),mapper.createObjectNode().put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight));
                        assertEquals(weight.max(minimum).multiply(rate).add(fee).setScale(2,RoundingMode.HALF_UP).doubleValue(),computed.path("total").asDouble(),row.toString());
                        cases.addObject().put("channel",channel.path("channelName").asText()).put("country",row.path("countryCode").asText()).put("zoneName",row.path("zoneName").asText()).put("weightKg",weight).set("expected",computed);
                    }
                }
                assertTrue(sourceRows.isEmpty());
                var israel=channel.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals("IL")).toList();
                assertEquals(normal?1:2,israel.size());for(var r:israel){assertEquals(normal?9:8,r.path("etaMinDays").asInt());assertEquals(15,r.path("etaMaxDays").asInt());}
            }
        }
        Files.writeString(Path.of("target/yanwen-express/billing-cases.json"),evidence.toPrettyString());
    }
}

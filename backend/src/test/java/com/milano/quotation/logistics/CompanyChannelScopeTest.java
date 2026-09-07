package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class CompanyChannelScopeTest {
    final tools.jackson.databind.ObjectMapper mapper=JsonMapper.builder().build();
    @Test void neverCrossesProvidersAndRejectsCodeNameConflicts(){
        var s=mapper.createObjectNode().put("enabled",true);var es=s.putArray("entries");
        var a=es.addObject().put("id","a").put("providerName","甲物流").put("channelName","普货专线").put("enabled",true);a.putArray("productCodes").add("AA");a.putArray("aliases").add("普货（优先）");
        var b=es.addObject().put("id","b").put("providerName","甲物流").put("channelName","带电专线").put("enabled",true);b.putArray("productCodes").add("BB");
        var scope=new CompanyChannelScope(s);
        assertTrue(scope.match("甲物流"," 普货(优先) ","").accepted());
        assertFalse(scope.match("乙物流","普货专线","AA").accepted());
        assertEquals("ambiguous",scope.match("甲物流","普货专线","BB").status());
        assertFalse(scope.match("甲物流","普货经济专线","").accepted());
        a.put("enabled",false);assertTrue(scope.match("甲物流","普货专线","").accepted(),"快照不受原对象修改影响");
        assertFalse(new CompanyChannelScope(s).match("甲物流","普货专线","").accepted());
    }
    @Test void filteredChannelDoesNotValidateInvalidNumericPrices()throws Exception{
        var parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
        var s=mapper.createObjectNode().put("enabled",true);s.putArray("entries").addObject().put("id","a").put("providerName","花海").put("channelName","公司普货").put("enabled",true);
        byte[] bytes;try(var w=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=w.createSheet("范围外渠道");var header=sheet.createRow(0);String[] labels={"国家","重量段","运费/KG","挂号费"};for(int i=0;i<labels.length;i++)header.createCell(i).setCellValue(labels[i]);
            var r=sheet.createRow(1);r.createCell(0).setCellValue("美国");r.createCell(1).setCellValue("0-1");r.createCell(2).setCellValue("错误的价格");r.createCell(3).setCellValue(-100);w.write(out);bytes=out.toByteArray();
        }
        var result=parser.parse(bytes,"花海.xlsx",new CompanyChannelScope(s));
        assertEquals(0,result.path("channels").size());assertEquals(1,result.path("filteredChannels").asInt());
        assertEquals("filtered",result.path("sheets").get(0).path("status").asText());
        assertFalse(result.toString().contains("关键价格"));assertFalse(result.toString().contains("adapter-required"));
    }
}

package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.row;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.bytes;

class LogisticsTitleIdentityTest {
    final tools.jackson.databind.ObjectMapper mapper=JsonMapper.builder().build();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));

    CompanyChannelScope scope(boolean enabled) {
        var snapshot=mapper.createObjectNode().put("enabled",true);var entries=snapshot.putArray("entries");
        var a=entries.addObject().put("id","a").put("providerName","花海").put("channelName","普货专线").put("enabled",enabled);
        a.putArray("aliases").add("普货（优先）");a.putArray("productCodes").add("AA");
        var b=entries.addObject().put("id","b").put("providerName","花海").put("channelName","带电专线").put("enabled",true);
        b.putArray("productCodes").add("BB");
        entries.addObject().put("id","other").put("providerName","其他物流").put("channelName","其他专线").putArray("productCodes").add("OTHER");
        return new CompanyChannelScope(snapshot);
    }

    ObjectNode parse(String sheetName,List<String> titles,String channel,String code,boolean xls,boolean enabled)throws Exception {
        try(Workbook book=xls?new HSSFWorkbook():new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=book.createSheet(sheetName);int r=0;
            for(var title:titles)sheet.createRow(r++).createCell(0).setCellValue(title);
            // Reordered columns ensure recognition follows headers, not fixed positions.
            var header=sheet.createRow(r++);
            String[] labels={"挂号费","国家","运费/KG","重量段",channel==null?"":"渠道名称",code==null?"":"产品代码"};
            for(int c=0;c<labels.length;c++)header.createCell(c).setCellValue(labels[c]);
            var row=sheet.createRow(r++);row.createCell(0).setCellValue(8);row.createCell(1).setCellValue("美国");row.createCell(2).setCellValue(50);row.createCell(3).setCellValue("0-1");
            if(channel!=null)row.createCell(4).setCellValue(channel);
            if(code!=null)row.createCell(5).setCellValue(code);
            sheet.createRow(r).createCell(0).setCellValue("带电专线"); // Footer/reference text is not identity evidence.
            book.write(output);
            return parser.parse(output.toByteArray(),"花海调价."+(xls?"xls":"xlsx"),scope(enabled));
        }
    }

    void assertChannel(ObjectNode result,String id) {
        assertEquals(1,result.path("channels").size(),result.toString());
        var channel=result.path("channels").get(0);
        assertEquals(id,channel.path("companyChannelId").asText());
        assertEquals(1,channel.path("rows").size());
        assertEquals(50,channel.path("rows").get(0).path("pricePerKg").asDouble());
        assertEquals(8,channel.path("rows").get(0).path("registrationFee").asDouble());
    }

    @Test void renamedSheetUsesExactTitleAndAliasInBothExcelFormats()throws Exception {
        for(boolean xls:List.of(false,true)) {
            assertChannel(parse("Sheet1",List.of("普货专线报价表"),null,null,xls,true),"a");
            var result=parse("九月新价格",List.of("渠道名称： 普货(优先)"),null,null,xls,true);
            assertChannel(result,"a");
            assertEquals("九月新价格",result.path("channels").get(0).path("rows").get(0).path("sourceSheet").asText());
        }
    }
    @Test void staleSheetNameDoesNotOverrideTitleOrProductCode()throws Exception {
        assertChannel(parse("带电专线",List.of("普货专线"),null,null,false,true),"a");
        assertChannel(parse("带电专线",List.of(),null,"AA",false,true),"a");
        assertChannel(parse("带电专线",List.of("产品代码：AA"),null,null,false,true),"a");
    }
    @Test void explicitChannelColumnKeepsRowIdentity()throws Exception {
        assertChannel(parse("带电专线",List.of("九月报价表"),"普货专线",null,false,true),"a");
        assertChannel(parse("Sheet1",List.of(),"未登记的显示名称","AA",false,true),"a");
    }
    @Test void sheetNameStillWorksWithoutBodyIdentity()throws Exception {
        assertChannel(parse("普货专线",List.of("花海九月报价表"),null,null,false,true),"a");
    }
    @Test void conflictingBodyEvidenceBlocksInsteadOfUpdating()throws Exception {
        for(var result:List.of(
                parse("Sheet1",List.of("普货专线"),null,"BB",false,true),
                parse("Sheet1",List.of("普货专线","带电专线"),null,null,false,true),
                parse("Sheet1",List.of(),"普货专线","BB",false,true))) {
            assertEquals(0,result.path("channels").size(),result.toString());
            assertEquals("match-pending",result.path("sheets").get(0).path("status").asText());
            assertEquals(0,result.path("priceCellsParsed").asInt());
        }
    }
    @Test void unknownDisabledAndOtherProviderTitlesCannotUpdateKnownSheet()throws Exception {
        for(var result:List.of(
                parse("普货专线",List.of("渠道名称：未登记线路"),null,null,false,true),
                parse("Sheet1",List.of("普货专线"),null,null,false,false),
                parse("Sheet1",List.of("其他专线","产品代码：OTHER"),null,null,false,true))) {
            assertEquals(0,result.path("channels").size(),result.toString());
            assertEquals(0,result.path("priceCellsParsed").asInt());
        }
    }

    @Test void standardTemplateWithBlankChannelUsesBodyTitle()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("新版价格");row(sheet,0,"普货专线");
            var headers=new java.util.ArrayList<>(LogisticsWorkbookService.HEADERS);
            headers.addAll(LogisticsSourceParser.EXTRA_HEADERS);
            var header=sheet.createRow(1);
            for(int c=0;c<headers.size();c++)header.createCell(c).setCellValue(headers.get(c));
            var data=sheet.createRow(2);
            var values=java.util.Map.of("区域名称","美国","国家简码","US","物流商","花海","起始重量","0","截止重量","1","运费单价","50","挂号费","8");
            values.forEach((key,value)->{int column=headers.indexOf(key);assertTrue(column>=0,key);data.createCell(column).setCellValue(value);});
            var parsed=parser.parse(bytes(book),"花海.xlsx",scope(true));
            assertEquals(1,parsed.path("channels").size(),parsed.toString());
            assertEquals("a",parsed.path("channels").get(0).path("companyChannelId").asText());
        }
    }

    @Test void renamedTongyouMatrixUsesTitleForChannelAndCountry()throws Exception {
        try(var book=new XSSFWorkbook()) {
            var sheet=book.createSheet("Sheet1");row(sheet,0,"加拿大专线");row(sheet,1,"","1区");
            row(sheet,2,"重量段","运费/KG","挂号费");row(sheet,3,"0-1",50,8);
            var snapshot=mapper.createObjectNode().put("enabled",true);
            snapshot.putArray("entries").addObject().put("id","ca").put("providerName","通邮").put("channelName","加拿大专线").put("enabled",true);
            var result=parser.parse(bytes(book),"通邮.xlsx",new CompanyChannelScope(snapshot));
            assertChannel(result,"ca");
            assertEquals("CA",result.path("channels").get(0).path("rows").get(0).path("countryCode").asText());
        }
    }
}

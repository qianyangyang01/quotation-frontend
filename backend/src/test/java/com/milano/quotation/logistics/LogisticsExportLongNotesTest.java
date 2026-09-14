package com.milano.quotation.logistics;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogisticsExportLongNotesTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="logistics.export.fixture",matches=".+")
    void suppliedDatasetExportsEveryPriceCell() throws Exception {
        var mapper=new ObjectMapper();var source=new java.util.ArrayList<ObjectNode>();
        for(var item:mapper.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("logistics.export.fixture")))))source.add((ObjectNode)item);
        var jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);var dataset=UUID.randomUUID();
        when(jdbc.sql(anyString()).param("dataset",dataset).param("version",null).query(org.mockito.ArgumentMatchers.<RowMapper<ObjectNode>>any()).list()).thenReturn(source);
        var bytes=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertNull(book.getSheet("规则说明"));
            assertEquals(source.size()+1,book.getSheet("版本信息").getLastRowNum());
            var sheet=book.getSheet("价格明细");int index=1;
            for(var channel:source)for(var value:channel.path("version").path("rows")) {
                var row=sheet.getRow(index++);
                for(int col=0;col<LogisticsWorkbookService.KEYS.length;col++) {
                    var original=value.path(LogisticsWorkbookService.KEYS[col]);var cell=row.getCell(col);
                    if(original.isNumber())assertEquals(original.asDouble(),cell.getNumericCellValue());
                    else assertEquals(original.isBoolean()?(original.asBoolean()?"是":"否"):original.isObject()||original.isArray()?original.toString():original.asText(""),cell.getStringCellValue());
                }
            }
            assertEquals(index-1,sheet.getLastRowNum());
            System.out.println("Full dataset workbook verified: channels="+source.size()+", priceRows="+(index-1)+", bytes="+bytes.length);
        }
        java.nio.file.Files.write(java.nio.file.Path.of("target/logistics-export-acceptance.xlsx"),bytes);
    }
    @Test void fullPriceExportKeepsPricesAndAllChannelNotes() throws Exception {
        var mapper=new ObjectMapper();var jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
        var dataset=UUID.randomUUID();
        var a=mapper.createObjectNode().put("provider","燕文").put("channel","普货").put("attribute","普货").put("id",UUID.randomUUID().toString()).put("status","published");
        var b=a.deepCopy().put("channel","特货").put("id",UUID.randomUUID().toString());
        var source=List.of(a,b);
        for(int i=0;i<source.size();i++) {
            var version=source.get(i).putObject("version").put("sourceNotes",randomText(39556+i*1727));
            version.putArray("rows").addObject().put("areaName","德国").put("countryCode","DE").put("weightFromKg",0).put("weightToKg",1).put("pricePerKg",52+i).put("registrationFee",23);
        }
        when(jdbc.sql(anyString()).param("dataset",dataset).param("version",null).query(org.mockito.ArgumentMatchers.<RowMapper<ObjectNode>>any()).list()).thenReturn(source);
        var bytes=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","");
        var compact=new LogisticsExportService(jdbc,mapper).prices(dataset,null,"","","",null,false);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            try(var filtered=new XSSFWorkbook(new ByteArrayInputStream(compact))) {
                assertEquals(2,filtered.getNumberOfSheets());
                assertNull(filtered.getSheet("规则说明"));
                for(int row=0;row<=book.getSheet("价格明细").getLastRowNum();row++) {
                    var original=book.getSheet("价格明细").getRow(row);
                    var actual=filtered.getSheet("价格明细").getRow(row);
                    assertEquals(original.getLastCellNum(),actual.getLastCellNum());
                    for(int col=0;col<original.getLastCellNum();col++)assertEquals(original.getCell(col).toString(),actual.getCell(col).toString());
                }
            }
            assertEquals(3,book.getNumberOfSheets());
            assertEquals(2,book.getSheet("价格明细").getLastRowNum());
            assertEquals(3,book.getSheet("版本信息").getLastRowNum());
            var notes=book.getSheet("规则说明");
            for(var item:source) {
                var restored=new StringBuilder();
                for(int row=2;row<=notes.getLastRowNum();row++)if(notes.getRow(row).getCell(1).getStringCellValue().equals(item.path("channel").asText()))restored.append(notes.getRow(row).getCell(2).getStringCellValue());
                assertEquals(item.path("version").path("sourceNotes").asText(),restored.toString());
            }
        }
    }
    @Test void longNotesRoundTripWithoutTruncationOrBrokenUnicode() throws Exception {
        for(var text:new String[]{"", "普通说明", randomText(32767), randomText(32768),
                randomText(32766)+"🚚\r\n"+randomText(48000), "=HYPERLINK(\"https://example.test\")"}) {
            try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
                var sheet=book.createSheet("规则说明");
                int end=LogisticsExportService.sourceNotesRows(sheet,2,"燕文","渠道A",text);
                book.write(bytes);
                try(var read=new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
                    var joined=new StringBuilder();
                    for(int row=2;row<end;row++) {
                        var output=read.getSheetAt(0).getRow(row);
                        assertEquals("燕文",output.getCell(0).getStringCellValue());
                        assertEquals("渠道A",output.getCell(1).getStringCellValue());
                        assertEquals(CellType.STRING,output.getCell(2).getCellType());
                        String chunk=output.getCell(2).getStringCellValue();
                        assertTrue(chunk.length()<=32767);
                        if(!chunk.isEmpty())assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));
                        assertEquals((row-1)+" / "+(end-2),output.getCell(3).getStringCellValue());
                        joined.append(chunk);
                    }
                    assertEquals(text,joined.toString());
                }
            }
        }
    }
    private static String randomText(int length){var random=new java.util.Random(length);var text=new StringBuilder();for(int i=0;i<length;i++)text.append((char)(0x4e00+random.nextInt(2000)));return text.toString();}
}

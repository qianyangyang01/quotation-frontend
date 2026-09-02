package com.milano.quotation.imports;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamingPurchaseWorkbookReaderTest {
    @Test void readsOriginalWorkbookWithEmbeddedImageButMapsOnlyCellData()throws Exception{
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("固定采购表");var header=sheet.createRow(0);
            header.createCell(0).setCellValue("SKU");header.createCell(1).setCellValue("采购价");header.createCell(2).setCellValue("产品图片");
            var data=sheet.createRow(1);data.createCell(0).setCellValue("REAL-123");data.createCell(1).setCellValue(12.5);data.createCell(2).setCellValue("https://example.com/image.png");
            var png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6zQAAAABJRU5ErkJggg==");
            int picture=workbook.addPicture(png,org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
            var anchor=workbook.getCreationHelper().createClientAnchor();anchor.setCol1(2);anchor.setRow1(1);anchor.setCol2(3);anchor.setRow2(2);
            sheet.createDrawingPatriarch().createPicture(anchor,picture);workbook.write(output);
            var mapped=new java.util.ArrayList<PurchaseImportRowMapper.MappedRow>();
            var mapper=new PurchaseImportRowMapper(tools.jackson.databind.json.JsonMapper.builder().build());
            var result=new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(output.toByteArray()),row->mapped.add(mapper.map(row.sourceSheet(),row.sourceRow(),row.values(),row.schema())));
            assertEquals(1,result.totalRows());assertEquals("REAL-123",mapped.getFirst().sku());
            assertEquals(12.5,mapped.getFirst().payload().path("purchasePriceCny").asDouble());
            for(var field:java.util.List.of("image","productImage","physicalImage"))assertEquals("",mapped.getFirst().payload().path(field).asText());
        }
    }
    @Test void streamsRowsWithoutLoadingPreviewPayload()throws Exception{var file=workbook(1_000);var count=new AtomicInteger();var result=new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(file),row->{assertTrue(row.values().length>=2);count.incrementAndGet();});assertEquals(1_000,count.get());assertEquals(1,result.sheets().size());assertTrue(result.sheets().getFirst().recognized());}
    @Test void keepsEveryLegacyValueOnItsOwnPhysicalRowWithoutFillingFromAdjacentRows()throws Exception{
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("陈晨");var header=sheet.createRow(0);
            header.createCell(0).setCellValue("SKU");header.createCell(4).setCellValue("克重/g");header.createCell(6).setCellValue("1件运费");header.createCell(7).setCellValue("报价");header.createCell(9).setCellValue("含票价");
            var first=sheet.createRow(1);first.createCell(0).setCellValue("SKU-A");first.createCell(4).setCellValue(70);first.createCell(6).setCellValue(1.7);first.createCell(7).setCellValue(6);first.createCell(9).setCellValue(6.18);
            var second=sheet.createRow(3);second.createCell(0).setCellValue("SKU-B");second.createCell(6).setCellValue(1.1);second.createCell(7).setCellValue(9.69);
            workbook.write(output);
            var rows=new java.util.ArrayList<StreamingPurchaseWorkbookReader.RawRow>();new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(output.toByteArray()),rows::add);
            assertEquals(2,rows.size());assertEquals(2,rows.get(0).sourceRow());assertEquals(4,rows.get(1).sourceRow());
            assertLegacyValue(rows.get(0),PurchaseWorkbookSchema.Field.SKU,"SKU-A");assertLegacyValue(rows.get(0),PurchaseWorkbookSchema.Field.WEIGHT,"70");assertLegacyValue(rows.get(0),PurchaseWorkbookSchema.Field.FREIGHT1,"1.7");assertLegacyValue(rows.get(0),PurchaseWorkbookSchema.Field.BASE_PRICE,"6");assertLegacyValue(rows.get(0),PurchaseWorkbookSchema.Field.TAX_INCLUDED_PRICE,"6.18");
            assertLegacyValue(rows.get(1),PurchaseWorkbookSchema.Field.SKU,"SKU-B");assertLegacyValue(rows.get(1),PurchaseWorkbookSchema.Field.WEIGHT,"");assertLegacyValue(rows.get(1),PurchaseWorkbookSchema.Field.FREIGHT1,"1.1");assertLegacyValue(rows.get(1),PurchaseWorkbookSchema.Field.BASE_PRICE,"9.69");assertLegacyValue(rows.get(1),PurchaseWorkbookSchema.Field.TAX_INCLUDED_PRICE,"");
        }
    }
    @Test void declaresTwoHundredThousandRowSafetyLimit(){assertEquals(200_000,StreamingPurchaseWorkbookReader.MAX_ROWS);}
    @Test void findsMovedHeadersAcrossArbitrarySheetsAndKeepsEmptyRecognizedSheet()throws Exception{try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new ByteArrayOutputStream()){workbook.createSheet("说明页").createRow(0).createCell(0).setCellValue("填写说明");var data=workbook.createSheet("任意名称");data.createRow(0).createCell(0).setCellValue("标题");var header=data.createRow(2);header.createCell(0).setCellValue("AI建议");header.createCell(3).setCellValue(" 克重（g）* ");header.createCell(7).setCellValue(" sku * ");header.createCell(10).setCellValue("采购价");var row=data.createRow(3);row.createCell(0).setCellValue("ignore me");row.createCell(3).setCellValue("1600g左右");row.createCell(7).setCellValue("SKU-MOVED");row.createCell(10).setCellValue(12.5);var empty=workbook.createSheet("空数据页");var emptyHeader=empty.createRow(0);emptyHeader.createCell(5).setCellValue("SKU编码");workbook.write(output);var rows=new java.util.ArrayList<StreamingPurchaseWorkbookReader.RawRow>();var result=new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(output.toByteArray()),rows::add);assertEquals(1,rows.size());assertEquals("SKU-MOVED",rows.getFirst().schema().value(rows.getFirst().values(),PurchaseWorkbookSchema.Field.SKU,new java.util.ArrayList<>()));assertEquals(3,result.sheets().size());assertFalse(result.sheets().get(0).recognized());assertEquals(3,result.sheets().get(1).headerRow());assertEquals(1,result.sheets().get(1).unknownColumns().size());assertTrue(result.sheets().get(2).recognized());assertEquals(0,result.sheets().get(2).dataRows());}}
    @Test void onlyWorkbookWithoutSkuHeaderIsBlocked()throws Exception{try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new ByteArrayOutputStream()){workbook.createSheet("说明").createRow(0).createCell(0).setCellValue("没有采购表");workbook.write(output);var error=assertThrows(com.milano.quotation.common.AppException.class,()->new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(output.toByteArray()),row->{}));assertTrue(error.getMessage().contains("整个工作簿未找到SKU列"));}}
    private static void assertLegacyValue(StreamingPurchaseWorkbookReader.RawRow row,PurchaseWorkbookSchema.Field field,String expected){assertEquals(expected,row.schema().value(row.values(),field,new java.util.ArrayList<>()));}
    private static byte[] workbook(int rows)throws Exception{try(var workbook=new SXSSFWorkbook(100);var output=new ByteArrayOutputStream()){var sheet=workbook.createSheet("采购产品导入");var header=sheet.createRow(0);for(int i=0;i<PurchaseWorkbookService.HEADERS.size();i++)header.createCell(i).setCellValue(PurchaseWorkbookService.HEADERS.get(i));for(int r=1;r<=rows;r++){var row=sheet.createRow(r);row.createCell(0).setCellValue("SKU-"+r);row.createCell(1).setCellValue("商品");}workbook.write(output);workbook.dispose();return output.toByteArray();}}
}

package com.milano.quotation.imports;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamingPurchaseWorkbookReaderTest {
    @Test void streamsRowsWithoutLoadingPreviewPayload()throws Exception{var file=workbook(1_000);var count=new AtomicInteger();new StreamingPurchaseWorkbookReader().read(new ByteArrayInputStream(file),row->{assertEquals(32,row.values().length);count.incrementAndGet();});assertEquals(1_000,count.get());}
    @Test void declaresTwoHundredThousandRowSafetyLimit(){assertEquals(200_000,StreamingPurchaseWorkbookReader.MAX_ROWS);}
    private static byte[] workbook(int rows)throws Exception{try(var workbook=new SXSSFWorkbook(100);var output=new ByteArrayOutputStream()){var sheet=workbook.createSheet("采购产品导入");var header=sheet.createRow(0);for(int i=0;i<PurchaseWorkbookService.HEADERS.size();i++)header.createCell(i).setCellValue(PurchaseWorkbookService.HEADERS.get(i));for(int r=1;r<=rows;r++){var row=sheet.createRow(r);row.createCell(0).setCellValue("SKU-"+r);row.createCell(1).setCellValue("商品");}workbook.write(output);workbook.dispose();return output.toByteArray();}}
}

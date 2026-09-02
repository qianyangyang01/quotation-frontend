package com.milano.quotation.imports;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit scale gate: run with -Dtest=PurchaseImportScaleBenchmark test. */
class PurchaseImportScaleBenchmark {
    @Test void streamsOneHundredThousandRows()throws Exception{assertStreams(100_000);}
    @Test void acceptsTwoHundredThousandRowBoundary()throws Exception{assertStreams(200_000);}
    private static void assertStreams(int expected)throws Exception{File file=File.createTempFile("purchase-scale-",".xlsx");try{try(var workbook=new SXSSFWorkbook(200);var output=new BufferedOutputStream(new FileOutputStream(file))){var sheet=workbook.createSheet("采购产品导入");var header=sheet.createRow(0);for(int i=0;i<PurchaseWorkbookService.HEADERS.size();i++)header.createCell(i).setCellValue(PurchaseWorkbookService.HEADERS.get(i));for(int r=1;r<=expected;r++){var row=sheet.createRow(r);row.createCell(0).setCellValue("SKU-"+r);row.createCell(1).setCellValue("商品");}workbook.write(output);workbook.dispose();}var count=new AtomicInteger();try(var input=new BufferedInputStream(new FileInputStream(file))){new StreamingPurchaseWorkbookReader().read(input,row->count.incrementAndGet());}assertEquals(expected,count.get());}finally{file.delete();}}
}

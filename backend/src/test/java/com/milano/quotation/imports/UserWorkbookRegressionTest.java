package com.milano.quotation.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UserWorkbookRegressionTest {
    @Test @EnabledIfSystemProperty(named="purchaseWorkbook",matches=".+")
    void parsesUserWorkbookWithUnknownColumnsWithoutLoadingImages()throws Exception{
        var file=Path.of(System.getProperty("purchaseWorkbook"));var rows=new ArrayList<StreamingPurchaseWorkbookReader.RawRow>();var reader=new StreamingPurchaseWorkbookReader();long started=System.nanoTime();StreamingPurchaseWorkbookReader.ReadResult result;
        try(var input=new FileInputStream(file.toFile())){result=reader.read(input,rows::add);}
        long elapsedMs=(System.nanoTime()-started)/1_000_000;var mapper=new PurchaseImportRowMapper(JsonMapper.builder().build());var mapped=new ArrayList<PurchaseImportRowMapper.MappedRow>();for(var row:rows)mapped.add(mapper.map("QA000001",row.sourceSheetIndex(),row.sourceSheet(),row.sourceRow(),row.values(),row.schema()));
        assertTrue(result.sheets().stream().anyMatch(StreamingPurchaseWorkbookReader.SheetSummary::recognized));assertFalse(rows.isEmpty());assertTrue(mapped.stream().allMatch(row->row.errors().isEmpty()),()->mapped.stream().flatMap(row->row.errors().stream()).toList().toString());assertTrue(elapsedMs<5_000,"text parse took "+elapsedMs+" ms");
        System.out.println("USER_WORKBOOK_QA bytes="+java.nio.file.Files.size(file)+" sheets="+result.sheets()+" rows="+rows.size()+" elapsedMs="+elapsedMs+" pending="+mapped.stream().filter(row->!row.payload().path("quoteReady").asBoolean()).count()+" warnings="+mapped.stream().mapToInt(row->row.warnings().size()).sum());
    }
}

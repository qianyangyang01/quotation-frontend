package com.milano.quotation.logistics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsWorksheetCompactorTest {
    @Test void removesOnlyFormattingCellsAndPreservesCoordinatesFormulasAndMerges() throws Exception {
        var xml = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" s=\"1\"/></row><row r=\"18\"><c r=\"A18\" t=\"inlineStr\"><is><t>法国</t></is></c><c r=\"B18\"><v>0.201</v></c><c r=\"C18\"><f>1/2</f><v>0.5</v></c><c r=\"D18\" s=\"1\"/></row></sheetData><mergeCells count=\"1\"><mergeCell ref=\"A18:A19\"/></mergeCells></worksheet>";
        var out = new ByteArrayOutputStream(); LogisticsWorksheetCompactor.copy(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), out);
        var compacted = out.toString(StandardCharsets.UTF_8);
        assertFalse(compacted.contains("A1\"")); assertTrue(compacted.contains("D18"));
        assertTrue(compacted.contains("法国")); assertTrue(compacted.contains("0.201")); assertTrue(compacted.contains("1/2")); assertTrue(compacted.contains("A18:A19"));
    }
}

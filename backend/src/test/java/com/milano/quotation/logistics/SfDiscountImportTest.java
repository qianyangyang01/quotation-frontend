package com.milano.quotation.logistics;

import tools.jackson.databind.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SfDiscountImportTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    byte[] workbook(String settlement,Object discount,Object price,Object net) throws Exception {
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("服装专线");
            Object[] headers=settlement==null?new Object[]{"国家名称","Code","运费/kg","折扣","操作费/票","重量段"}:new Object[]{"国家名称","Code","运费/kg","折扣",settlement,"操作费/票","重量段"};
            Object[] values=settlement==null?new Object[]{"法国","FR",price,discount,20,"0.201-0.4KG"}:new Object[]{"法国","FR",price,discount,net,20,"0.201-0.4KG"};
            for(int r=0;r<2;r++){var row=sheet.createRow(r);var valuesAt=r==0?headers:values;for(int c=0;c<valuesAt.length;c++){var cell=row.createCell(c);var v=valuesAt[c];if(v instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(String.valueOf(v));}}
            book.write(bytes);return bytes.toByteArray();
        }
    }
    JsonNode channel(byte[] bytes,String provider){return parser.parse(bytes,provider+".xlsx").path("channels").get(0);}
    @Test void settlementAliasesOverrideOriginalAndDiscountWithoutDiscountingOperationFee() throws Exception {
        for(var header:new String[]{"折后运费","结算运费","SF折后"}) {
            var c=channel(workbook(header,1,97,51),"顺丰");var row=c.path("rows").get(0);
            assertEquals(51,row.path("pricePerKg").asDouble());assertEquals(20,row.path("registrationFee").asDouble());
            assertEquals("E2",row.path("sourceSettlementRateCell").asText());assertEquals("97",row.path("sourceOriginalRate").asText());
            assertEquals(35.3,new LogisticsBillingEngine(mapper).calculate(c.path("rows"),mapper.createObjectNode().put("country","FR").put("weightKg",0.3)).path("total").asDouble());
        }
        assertEquals(70.6,channel(workbook("折后运费",0.7,98,70.6),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
    }
    @Test void explicitInvalidSettlementNeverFallsBackToOriginalOrDiscount() throws Exception {
        for(var bad:new Object[]{"",0,-1,"#VALUE!","待定"}) {
            var c=channel(workbook("折后运费",0.7,100,bad),"顺丰");assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("rows").get(0).path("quoteReady").asBoolean());
        }
    }
    @Test void discountOnlySupportsUnambiguousCoefficientsPercentagesAndChineseNotation() throws Exception {
        for(var discount:new Object[]{0.7,"70%","70％","7折"})assertEquals(70,channel(workbook(null,discount,100,0),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
        assertEquals(100,channel(workbook(null,1,100,0),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
        for(var bad:new Object[]{"",0,-0.1,7,70,1.1,"七折","#REF!"})assertFalse(channel(workbook(null,bad,100,0),"顺丰").path("rows").get(0).path("quoteReady").asBoolean());
    }
    @Test void otherProvidersIgnoreSfSettlementAndDiscountColumns() throws Exception {
        for(var provider:LogisticsSourceParser.PROVIDERS)if(!provider.equals("顺丰"))assertEquals(97,channel(workbook("折后运费",0.7,97,51),provider).path("rows").get(0).path("pricePerKg").asDouble(),provider);
    }
    @Test void handlesCachedFormulasPercentFormatsMergedDiscountsAndMissingBaseColumn() throws Exception {
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(null,0.7,100,0)));var bytes=new ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("0%"));
            sheet.getRow(1).getCell(3).setCellStyle(style);
            sheet.getRow(1).getCell(3).setCellFormula("7/10");book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.write(bytes);assertEquals(70,channel(bytes.toByteArray(),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
            sheet.getRow(1).getCell(3).setCellFormula("1/0");book.getCreationHelper().createFormulaEvaluator().evaluateAll();bytes.reset();book.write(bytes);
            assertFalse(channel(bytes.toByteArray(),"顺丰").path("quoteReady").asBoolean());
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook("折后运费",0.7,100,70)));var bytes=new ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);sheet.getRow(0).getCell(2).setCellValue("原价参考");
            sheet.getRow(1).getCell(4).setCellFormula("100*0.7");book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(bytes);
            assertEquals(70,channel(bytes.toByteArray(),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
            sheet.getRow(0).getCell(3).setCellValue("结算运费");bytes.reset();book.write(bytes);
            assertFalse(channel(bytes.toByteArray(),"顺丰").path("quoteReady").asBoolean());
        }
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook(null,0.7,100,0)));var bytes=new ByteArrayOutputStream()) {
            var sheet=book.getSheetAt(0);var second=sheet.createRow(2);
            second.createCell(0).setCellValue("法国");second.createCell(1).setCellValue("FR");second.createCell(2).setCellValue(120);second.createCell(4).setCellValue(20);second.createCell(5).setCellValue("0.401-1KG");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1,2,3,3));book.write(bytes);
            assertEquals(84,channel(bytes.toByteArray(),"顺丰").path("rows").get(1).path("pricePerKg").asDouble());
            sheet.getRow(0).getCell(3).setCellValue("备注");bytes.reset();book.write(bytes);
            assertEquals(100,channel(bytes.toByteArray(),"顺丰").path("rows").get(0).path("pricePerKg").asDouble());
        }
    }
    @Test @EnabledIfSystemProperty(named="sf.workbook",matches=".+")
    void realWorkbookEveryParsedSettlementMatchesItsSource() throws Exception {
        var parsed=parser.parse(Files.readAllBytes(Path.of(System.getProperty("sf.workbook"))),"顺丰.xlsx");int rows=0,changed=0;
        var expected=new java.util.HashMap<String,Double>();var priceSheets=new java.util.HashSet<String>();
        for(var c:parsed.path("channels"))for(var row:c.path("rows"))priceSheets.add(row.path("sourceSheet").asText());
        try(var pkg=org.apache.poi.openxml4j.opc.OPCPackage.open(System.getProperty("sf.workbook"),org.apache.poi.openxml4j.opc.PackageAccess.READ)) {
            var reader=new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);var strings=reader.getSharedStringsTable();
            var sheets=(org.apache.poi.xssf.eventusermodel.XSSFReader.SheetIterator)reader.getSheetsData();
            while(sheets.hasNext())try(var stream=sheets.next()) {
                if(!priceSheets.contains(sheets.getSheetName()))continue;
                var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                var cells=factory.newDocumentBuilder().parse(stream).getElementsByTagName("c");
                for(int i=0;i<cells.getLength();i++) {
                    var cell=(org.w3c.dom.Element)cells.item(i);var values=cell.getElementsByTagName("v");
                    if(values.getLength()>0||cell.getElementsByTagName("t").getLength()>0)try {var raw=values.getLength()>0?values.item(0).getTextContent():cell.getElementsByTagName("t").item(0).getTextContent();if(cell.getAttribute("t").equals("s"))raw=strings.getItemAt(Integer.parseInt(raw)).getString();expected.put(sheets.getSheetName()+"!"+cell.getAttribute("r"),Double.parseDouble(raw));}catch(NumberFormatException ignored){}
                }
            }
        }
        for(var c:parsed.path("channels"))for(var row:c.path("rows")) {
            if(!row.path("sourcePricingBasis").asText().equals("settlement"))continue;
            rows++;
            if(row.path("pricePerKg").asDouble()>0) {
                var value=expected.get(row.path("sourceSheet").asText()+"!"+row.path("sourceSettlementRateCell").asText());
                assertNotNull(value,row.path("sourceSheet")+":"+row.path("sourceSettlementRateCell"));assertEquals(value,row.path("pricePerKg").asDouble(),row.path("sourceSheet")+":"+row.path("sourceRow"));
                var original=expected.get(row.path("sourceSheet").asText()+"!"+row.path("sourceOriginalRateCell").asText());
                if(original!=null&&Double.compare(original,value)!=0)changed++;
            } else assertFalse(row.path("quoteReady").asBoolean());
        }
        assertTrue(rows>0);Files.createDirectories(Path.of("target/sf-discount"));
        Files.writeString(Path.of("target/sf-discount/real-workbook.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        System.out.println("SF_DISCOUNT_REAL_ROWS="+rows+" CHANGED="+changed);
    }
}

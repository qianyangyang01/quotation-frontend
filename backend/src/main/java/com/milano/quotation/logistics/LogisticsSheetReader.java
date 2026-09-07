package com.milano.quotation.logistics;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import javax.xml.stream.*;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Read one value-only sheet at a time; never load drawings or a complete XSSF workbook. */
final class LogisticsSheetReader implements AutoCloseable {
    private Path path;
    private OPCPackage pkg;
    private Workbook legacy, current;
    private XSSFReader.SheetIterator sheets;
    private SharedStrings strings;
    private StylesTable styles;
    private int index;
    private final Map<String,Boolean> hidden = new HashMap<>();
    private final Predicate<String> skip;
    private static XMLInputFactory xml() {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        return factory;
    }
    LogisticsSheetReader(byte[] bytes,String filename,Predicate<String> skip)throws Exception {
        this.skip=skip;
        try {
            if(!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")){legacy=WorkbookFactory.create(new ByteArrayInputStream(bytes));if(legacy.getNumberOfSheets()>300)throw new IOException("单个文件不能超过300张工作表");return;}
            path=Files.createTempFile("logistics-sheet-",".xlsx");Files.write(path,bytes);
            pkg=OPCPackage.open(path.toFile(),PackageAccess.READ);
            var reader=new XSSFReader(pkg);reader.setUseReadOnlySharedStringsTable(true);
            strings=reader.getSharedStringsTable();styles=reader.getStylesTable();
            try(var input=reader.getWorkbookData()) {
                var events=xml().createXMLStreamReader(input);
                while(events.hasNext())if(events.next()==XMLStreamConstants.START_ELEMENT&&events.getLocalName().equals("sheet"))
                    hidden.put(events.getAttributeValue(null,"name"),Set.of("hidden","veryHidden").contains(Objects.toString(events.getAttributeValue(null,"state"),"")));
                events.close();
            }
            if(hidden.size()>300)throw new IOException("单个文件不能超过300张工作表");
            sheets=(XSSFReader.SheetIterator)reader.getSheetsData();
        } catch(Exception e){close();throw e;}
    }
    boolean hasNext(){return legacy!=null?index<legacy.getNumberOfSheets():sheets.hasNext();}
    boolean isHidden(Sheet sheet){return legacy!=null?legacy.isSheetHidden(legacy.getSheetIndex(sheet)):hidden.getOrDefault(sheet.getSheetName(),false);}
    Sheet next()throws Exception {
        if(legacy!=null)return legacy.getSheetAt(index++);
        if(current!=null)current.close();
        current=new XSSFWorkbook();
        try(var input=sheets.next()) {
            var sheet=current.createSheet(sheets.getSheetName());
            if(skip.test(sheet.getSheetName()))return sheet;
            var events=xml().createXMLStreamReader(input);
            String address="",type="",value="";int style=-1,cells=0;boolean formula=false;
            var formats=new HashMap<Integer,CellStyle>();
            while(events.hasNext()) {
                int event=events.next();
                if(event==XMLStreamConstants.START_ELEMENT) {
                    switch(events.getLocalName()) {
                        case "c" -> {address=events.getAttributeValue(null,"r");type=Objects.toString(events.getAttributeValue(null,"t"),"");var s=events.getAttributeValue(null,"s");style=s==null?-1:Integer.parseInt(s);value="";formula=false;}
                        case "f" -> {formula=true;events.getElementText();}
                        case "v" -> value=events.getElementText();
                        case "t" -> {if(type.equals("inlineStr"))value+=events.getElementText();}
                        case "mergeCell" -> sheet.addMergedRegionUnsafe(CellRangeAddress.valueOf(events.getAttributeValue(null,"ref")));
                        default -> { }
                    }
                } else if(event==XMLStreamConstants.END_ELEMENT&&events.getLocalName().equals("c")&&(!value.isEmpty()||formula)) {
                    if(type.equals("s")){value=strings.getItemAt(Integer.parseInt(value)).getString();type="inlineStr";}
                    if(!formula&&value.isBlank())continue;
                    if(++cells>250_000)throw new IOException("工作表有效单元格超过安全读取上限："+sheet.getSheetName());
                    var ref=new CellReference(address);var row=sheet.getRow(ref.getRow());if(row==null)row=sheet.createRow(ref.getRow());var cell=row.createCell(ref.getCol());
                    if(formula&&value.isEmpty())cell.setCellErrorValue(FormulaError.NA.getCode());
                    else switch(type) {
                        case "s" -> cell.setCellValue(strings.getItemAt(Integer.parseInt(value)).getString());
                        case "inlineStr","str","d" -> cell.setCellValue(value);
                        case "b" -> cell.setCellValue(value.equals("1"));
                        case "e" -> cell.setCellErrorValue(FormulaError.forString(value).getCode());
                        default -> cell.setCellValue(Double.parseDouble(value));
                    }
                    if(style>=0&&styles!=null) {
                        var format=formats.get(style);
                        if(format==null){format=current.createCellStyle();format.setDataFormat(current.createDataFormat().getFormat(Objects.toString(styles.getStyleAt(style).getDataFormatString(),"General")));formats.put(style,format);}
                        cell.setCellStyle(format);
                    }
                }
            }
            events.close();return sheet;
        }
    }
    @Override public void close()throws IOException {
        try {if(current!=null)current.close();if(legacy!=null)legacy.close();if(pkg!=null)pkg.close();}
        finally {if(path!=null)Files.deleteIfExists(path);}
    }
}

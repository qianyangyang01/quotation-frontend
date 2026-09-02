package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

@Component
public class StreamingPurchaseWorkbookReader {
    static final int MAX_ROWS=200_000,HEADER_SCAN_ROWS=20;
    public ReadResult read(InputStream input,Consumer<RawRow> consumer){
        try(var pkg=OPCPackage.open(input)){
            var reader=new XSSFReader(pkg);StylesTable styles=reader.getStylesTable();SharedStrings strings=reader.getSharedStringsTable();var sheets=(XSSFReader.SheetIterator)reader.getSheetsData();int[] total={0};var summaries=new ArrayList<SheetSummary>();
            int sheetIndex=0;while(sheets.hasNext())try(var sheet=sheets.next()){sheetIndex++;var name=safeSheetName(sheets.getSheetName());var summary=parse(sheet,styles,strings,name,sheetIndex,row->{if(++total[0]>MAX_ROWS)throw AppException.unprocessable("单个导入任务最多20万行");consumer.accept(row);});summaries.add(summary);}
            if(summaries.stream().noneMatch(SheetSummary::recognized))throw AppException.unprocessable("整个工作簿未找到SKU列，无法识别采购数据表（已扫描："+summaries.stream().map(SheetSummary::sheetName).reduce((a,b)->a+"、"+b).orElse("无工作表")+"）");
            return new ReadResult(List.copyOf(summaries),total[0]);
        }catch(AppException e){throw e;}catch(Exception e){throw AppException.unprocessable("Excel流式解析失败："+safe(e));}
    }
    private SheetSummary parse(InputStream sheet,StylesTable styles,SharedStrings strings,String name,int sheetIndex,Consumer<RawRow> consumer)throws Exception{var parser=XMLHelper.newXMLReader();var handler=new Handler(name,sheetIndex,consumer);parser.setContentHandler(new XSSFSheetXMLHandler(styles,null,strings,handler,new DataFormatter(Locale.CHINA),false));parser.parse(new InputSource(sheet));return handler.summary();}
    private static final class Handler implements XSSFSheetXMLHandler.SheetContentsHandler{
        private final String sheetName;private final int sheetIndex;private final Consumer<RawRow> consumer;private String[] values;private int rowNumber,maxColumn;private PurchaseWorkbookSchema schema;private int dataRows,ignoredRows;
        Handler(String sheetName,int sheetIndex,Consumer<RawRow> consumer){this.sheetName=sheetName;this.sheetIndex=sheetIndex;this.consumer=consumer;}
        public void startRow(int rowNum){rowNumber=rowNum+1;values=new String[32];Arrays.fill(values,"");maxColumn=0;}
        public void endRow(int rowNum){var row=Arrays.copyOf(values,Math.max(1,maxColumn));if(schema==null){if(rowNumber<=HEADER_SCAN_ROWS&&PurchaseWorkbookSchema.hasSkuHeader(row))schema=PurchaseWorkbookSchema.identifyOrNull(row,rowNumber);return;}if(rowNumber<=schema.headerRow())return;if(isDefaultOnly(row,schema)){ignoredRows++;return;}dataRows++;consumer.accept(new RawRow(sheetName,sheetIndex,rowNumber,row,schema));}
        public void cell(String ref,String formatted,XSSFComment comment){int col=column(ref);if(col<0)return;if(col>=values.length){int old=values.length;values=Arrays.copyOf(values,Math.max(col+1,old*2));Arrays.fill(values,old,values.length,"");}values[col]=formatted==null?"":formatted.trim();maxColumn=Math.max(maxColumn,col+1);}
        public void headerFooter(String text,boolean isHeader,String tagName){}
        SheetSummary summary(){return schema==null?new SheetSummary(sheetName,false,0,List.of(),List.of(),List.of(),0,0):new SheetSummary(sheetName,true,schema.headerRow(),schema.recognizedColumns(),schema.unknownColumns(),schema.missingColumns(),dataRows,ignoredRows);}
        private static int column(String ref){int value=0;for(int i=0;i<ref.length()&&Character.isLetter(ref.charAt(i));i++)value=value*26+(Character.toUpperCase(ref.charAt(i))-'A'+1);return value-1;}
    }
    static boolean isDefaultOnly(String[] values,PurchaseWorkbookSchema schema){for(var field:PurchaseWorkbookSchema.Field.values())for(var index:schema.columns(field)){var value=index<values.length&&values[index]!=null?values[index].trim():"";if(value.isBlank())continue;if(!value.matches("0(?:\\.0+)?"))return false;}return true;}
    private static String safeSheetName(String value){var result=value==null?"未命名工作表":value.trim();return result.substring(0,Math.min(128,result.length()));}
    private static String safe(Exception e){var s=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();return s.substring(0,Math.min(300,s.length()));}
    public record RawRow(String sourceSheet,int sourceSheetIndex,int sourceRow,String[] values,PurchaseWorkbookSchema schema){RawRow(int sourceRow,String[] values){this("采购产品导入",1,sourceRow,values,new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY));}}
    public record SheetSummary(String sheetName,boolean recognized,int headerRow,List<String>recognizedColumns,List<String>unknownColumns,List<String>missingColumns,int dataRows,int ignoredRows){}
    public record ReadResult(List<SheetSummary>sheets,int totalRows){}
}

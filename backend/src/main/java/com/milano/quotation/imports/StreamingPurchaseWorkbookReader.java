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
    static final int MAX_ROWS=200_000;

    public void read(InputStream input, Consumer<RawRow> consumer) {
        try(var pkg=OPCPackage.open(input)) {
            var reader=new XSSFReader(pkg);StylesTable styles=reader.getStylesTable();SharedStrings strings=reader.getSharedStringsTable();
            var sheets=(XSSFReader.SheetIterator)reader.getSheetsData();int[] total={0};boolean[] recognized={false};
            while(sheets.hasNext())try(var sheet=sheets.next()){
                var sheetName=safeSheetName(sheets.getSheetName());
                parse(sheet,styles,strings,sheetName,row->{if(++total[0]>MAX_ROWS)throw AppException.unprocessable("单个导入任务最多20万行");consumer.accept(row);},recognized);
            }
            if(!recognized[0])throw AppException.unprocessable("没有找到可识别的采购数据工作表");
        } catch(AppException e){throw e;} catch(Exception e){throw AppException.unprocessable("Excel流式解析失败："+safe(e));}
    }

    private void parse(InputStream sheet, StylesTable styles, SharedStrings strings, String sheetName, Consumer<RawRow> consumer, boolean[] recognized)throws Exception {
        var parser=XMLHelper.newXMLReader();var handler=new Handler(sheetName,consumer,recognized);
        parser.setContentHandler(new XSSFSheetXMLHandler(styles,null,strings,handler,new DataFormatter(Locale.CHINA),false));
        parser.parse(new InputSource(sheet));
    }

    private static final class Handler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final String sheetName;private final Consumer<RawRow> consumer;private final boolean[] recognized;
        private String[] values;private int rowNumber;private PurchaseWorkbookSchema schema;
        Handler(String sheetName,Consumer<RawRow> consumer,boolean[] recognized){this.sheetName=sheetName;this.consumer=consumer;this.recognized=recognized;}
        public void startRow(int rowNum){rowNumber=rowNum+1;values=new String[64];Arrays.fill(values,"");}
        public void endRow(int rowNum){
            if(rowNum==0){schema=PurchaseWorkbookSchema.identify(values,sheetName);recognized[0]=true;return;}
            if(schema==null)return;
            for(int i=schema.width();i<values.length;i++)if(!values[i].isBlank())throw AppException.unprocessable("工作表“"+sheetName+"”第"+rowNumber+"行的重复尾列"+PurchaseWorkbookSchema.column(i)+"存在数据，请删除后重新上传");
            if(isDefaultOnly(values,schema))return;
            consumer.accept(new RawRow(sheetName,rowNumber,Arrays.copyOf(values,schema.width()),schema));
        }
        public void cell(String cellReference,String formattedValue,XSSFComment comment){int column=column(cellReference);if(column>=0&&column<values.length)values[column]=formattedValue==null?"":formattedValue.trim();}
        public void headerFooter(String text,boolean isHeader,String tagName){}
        private static int column(String reference){int value=0;for(int i=0;i<reference.length()&&Character.isLetter(reference.charAt(i));i++)value=value*26+(Character.toUpperCase(reference.charAt(i))-'A'+1);return value-1;}
    }

    static boolean isDefaultOnly(String[] values,PurchaseWorkbookSchema schema){
        for(int i=0;i<schema.width();i++){
            var value=i<values.length&&values[i]!=null?values[i].trim():"";
            if(value.isBlank())continue;
            if(i==schema.taxIncludedPrice()&&value.matches("0(?:\\.0+)?"))continue;
            return false;
        }
        return true;
    }
    private static String safeSheetName(String value){var result=value==null?"未命名工作表":value.trim();return result.substring(0,Math.min(128,result.length()));}
    private static String safe(Exception e){var s=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();return s.substring(0,Math.min(200,s.length()));}
    public record RawRow(String sourceSheet,int sourceRow,String[] values,PurchaseWorkbookSchema schema){RawRow(int sourceRow,String[] values){this("采购产品导入",sourceRow,values,new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY));}}
}

package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.apache.poi.util.XMLHelper;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

@Component
public class StreamingPurchaseWorkbookReader {
    static final int MAX_ROWS=200_000;
    public void read(InputStream input,Consumer<RawRow> consumer){
        try(var pkg=OPCPackage.open(input)){var reader=new XSSFReader(pkg);StylesTable styles=reader.getStylesTable();SharedStrings strings=reader.getSharedStringsTable();var sheets=(XSSFReader.SheetIterator)reader.getSheetsData();boolean found=false;while(sheets.hasNext()){try(var sheet=sheets.next()){if(!"采购产品导入".equals(sheets.getSheetName()))continue;found=true;parse(sheet,styles,strings,consumer);break;}}if(!found)throw AppException.unprocessable("没有找到工作表“采购产品导入”");}
        catch(AppException e){throw e;}catch(Exception e){throw AppException.unprocessable("Excel流式解析失败："+safe(e));}
    }
    private void parse(InputStream sheet,StylesTable styles,SharedStrings strings,Consumer<RawRow> consumer)throws Exception{XMLReader parser=XMLHelper.newXMLReader();var handler=new Handler(consumer);parser.setContentHandler(new XSSFSheetXMLHandler(styles,null,strings,handler,new org.apache.poi.ss.usermodel.DataFormatter(Locale.CHINA),false));parser.parse(new InputSource(sheet));if(!handler.headerValidated)throw AppException.unprocessable("采购模板没有有效表头");}
    private static final class Handler implements XSSFSheetXMLHandler.SheetContentsHandler{
        private final Consumer<RawRow> consumer;private String[] values;private int rowNumber;private boolean headerValidated;
        Handler(Consumer<RawRow> consumer){this.consumer=consumer;}
        public void startRow(int rowNum){rowNumber=rowNum+1;values=new String[PurchaseWorkbookService.HEADERS.size()];Arrays.fill(values,"");}
        public void endRow(int rowNum){if(rowNum==0){for(int i=0;i<values.length;i++)if(!PurchaseWorkbookService.HEADERS.get(i).equals(values[i]))throw AppException.unprocessable("模板列头不匹配，第"+(i+1)+"列应为“"+PurchaseWorkbookService.HEADERS.get(i)+"”");headerValidated=true;return;}if(rowNum>MAX_ROWS)throw AppException.unprocessable("单个导入任务最多20万行");if(Arrays.stream(values).anyMatch(v->v!=null&&!v.isBlank()))consumer.accept(new RawRow(rowNumber,values.clone()));}
        public void cell(String cellReference,String formattedValue,XSSFComment comment){int column=column(cellReference);if(column>=0&&column<values.length)values[column]=formattedValue==null?"":formattedValue.trim();}
        public void headerFooter(String text,boolean isHeader,String tagName){}
        private static int column(String reference){int value=0;for(int i=0;i<reference.length()&&Character.isLetter(reference.charAt(i));i++)value=value*26+(Character.toUpperCase(reference.charAt(i))-'A'+1);return value-1;}
    }
    private static String safe(Exception e){var s=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();return s.substring(0,Math.min(200,s.length()));}
    public record RawRow(int sourceRow,String[] values){}
}

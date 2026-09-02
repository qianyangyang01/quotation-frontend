package com.milano.quotation.imports;

import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;

@Service
public class PurchaseEmbeddedImageImportService {
    private final PurchaseImportRowRepository rows;private final AssetStorageService storage;
    public PurchaseEmbeddedImageImportService(PurchaseImportRowRepository rows,AssetStorageService storage){this.rows=rows;this.storage=storage;}

    @Transactional
    public int process(UUID jobId,InputStream input){return processDetailed(jobId,input).imported();}
    @Transactional public ImageResult processDetailed(UUID jobId,InputStream input){
        try(var workbook=new XSSFWorkbook(input)){
            var formatter=new DataFormatter(Locale.CHINA);int imported=0,failed=0;
            for(int index=0;index<workbook.getNumberOfSheets();index++){
                var sheet=workbook.getSheetAt(index);PurchaseWorkbookSchema schema=null;
                for(int rowIndex=0;rowIndex<Math.min(StreamingPurchaseWorkbookReader.HEADER_SCAN_ROWS,sheet.getLastRowNum()+1);rowIndex++){var headerRow=sheet.getRow(rowIndex);if(headerRow==null)continue;var header=new String[Math.max(1,headerRow.getLastCellNum())];for(int col=0;col<header.length;col++){var cell=headerRow.getCell(col);header[col]=cell==null?"":formatter.formatCellValue(cell).trim();}schema=PurchaseWorkbookSchema.identifyOrNull(header,rowIndex+1);if(schema!=null)break;}
                if(schema==null)continue;var drawing=sheet.getDrawingPatriarch();if(drawing==null)continue;
                for(var shape:drawing.getShapes())if(shape instanceof XSSFPicture picture){var anchor=picture.getClientAnchor();var type=anchor.getCol1()==schema.productImage()?"product":anchor.getCol1()==schema.physicalImage()?"physical":null;if(type==null)continue;int sourceRow=anchor.getRow1()+1;if(sourceRow<=schema.headerRow())continue;
                    var staged=rows.findFirstByJobIdAndSourceSheetAndSourceRow(jobId,sheet.getSheetName(),sourceRow).orElse(null);if(staged==null||!"valid".equals(staged.validationStatus))continue;
                    try{var asset=storage.storeTemporaryImageIndependent(picture.getPictureData().getData(),staged.sku+"-"+type+"."+picture.getPictureData().suggestFileExtension(),jobId);var payload=(tools.jackson.databind.node.ObjectNode)staged.payload;
                    if(type.equals("product")){staged.productAssetId=asset.id;payload.put("productImage","/api/v1/assets/"+asset.id);payload.put("image","/api/v1/assets/"+asset.id);}else{staged.physicalAssetId=asset.id;payload.put("physicalImage","/api/v1/assets/"+asset.id);}imported++;}catch(Exception imageError){failed++;var payload=(tools.jackson.databind.node.ObjectNode)staged.payload;var existing=payload.get("importWarnings");var warningArray=existing instanceof tools.jackson.databind.node.ArrayNode array?array:payload.putArray("importWarnings");warningArray.add(type+"图片处理失败："+(imageError.getMessage()==null?imageError.getClass().getSimpleName():imageError.getMessage()));}
                }
            }
            return new ImageResult(imported,failed);
        }catch(Exception e){throw new IllegalStateException("嵌入图片解析失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);}
    }
    public record ImageResult(int imported,int failed){}
}

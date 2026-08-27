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
    public int process(UUID jobId,InputStream input){
        try(var workbook=new XSSFWorkbook(input)){
            var formatter=new DataFormatter(Locale.CHINA);int imported=0;
            for(int index=0;index<workbook.getNumberOfSheets();index++){
                var sheet=workbook.getSheetAt(index);var header=new String[64];Arrays.fill(header,"");var headerRow=sheet.getRow(0);if(headerRow!=null)for(int col=0;col<header.length;col++){var cell=headerRow.getCell(col);if(cell!=null)header[col]=formatter.formatCellValue(cell).trim();}
                var schema=PurchaseWorkbookSchema.identify(header,sheet.getSheetName());var drawing=sheet.getDrawingPatriarch();if(drawing==null)continue;
                for(var shape:drawing.getShapes())if(shape instanceof XSSFPicture picture){var anchor=picture.getClientAnchor();var type=anchor.getCol1()==schema.productImage()?"product":anchor.getCol1()==schema.physicalImage()?"physical":null;if(type==null)continue;int sourceRow=anchor.getRow1()+1;if(sourceRow<2)continue;
                    var staged=rows.findFirstByJobIdAndSourceSheetAndSourceRow(jobId,sheet.getSheetName(),sourceRow).orElse(null);if(staged==null||!"valid".equals(staged.validationStatus))continue;
                    var asset=storage.storeTemporaryImageIndependent(picture.getPictureData().getData(),staged.sku+"-"+type+"."+picture.getPictureData().suggestFileExtension(),jobId);var payload=(tools.jackson.databind.node.ObjectNode)staged.payload;
                    if(type.equals("product")){staged.productAssetId=asset.id;payload.put("productImage","/api/v1/assets/"+asset.id);payload.put("image","/api/v1/assets/"+asset.id);}else{staged.physicalAssetId=asset.id;payload.put("physicalImage","/api/v1/assets/"+asset.id);}imported++;
                }
            }
            return imported;
        }catch(Exception e){throw new IllegalStateException("嵌入图片解析失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);}
    }
}

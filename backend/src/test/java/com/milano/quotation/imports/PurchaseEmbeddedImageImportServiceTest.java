package com.milano.quotation.imports;

import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseEmbeddedImageImportServiceTest {
    @Test void importsInternationalPhysicalAndProductImagesBySheetAndRow() throws Exception {
        var rows=mock(PurchaseImportRowRepository.class);var storage=mock(AssetStorageService.class);var service=new PurchaseEmbeddedImageImportService(rows,storage);var jobId=UUID.randomUUID();
        var staged=new PurchaseImportRow();staged.id=UUID.randomUUID();staged.jobId=jobId;staged.sourceSheet="国际站";staged.sourceRow=2;staged.sku="SKU-IMG";staged.validationStatus="valid";staged.payload=JsonMapper.builder().build().createObjectNode().put("sku",staged.sku);
        when(rows.findFirstByJobIdAndSourceSheetAndSourceRow(jobId,"国际站",2)).thenReturn(Optional.of(staged));
        var physical=mock(AssetObject.class);physical.id=UUID.randomUUID();var product=mock(AssetObject.class);product.id=UUID.randomUUID();when(storage.storeTemporaryImageIndependent(any(),contains("physical"),eq(jobId))).thenReturn(physical);when(storage.storeTemporaryImageIndependent(any(),contains("product"),eq(jobId))).thenReturn(product);
        byte[] workbook;
        try(var source=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var schema=new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.INTERNATIONAL);var sheet=source.createSheet("国际站");var header=sheet.createRow(0);for(int index=0;index<PurchaseWorkbookSchema.INTERNATIONAL_HEADERS.size();index++)header.createCell(index).setCellValue(PurchaseWorkbookSchema.INTERNATIONAL_HEADERS.get(index));sheet.createRow(1).createCell(schema.sku()).setCellValue("SKU-IMG");
            var bytes=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2n8sAAAAASUVORK5CYII=");var drawing=sheet.createDrawingPatriarch();
            addPicture(source,drawing,bytes,schema.physicalImage());addPicture(source,drawing,bytes,schema.productImage());source.write(output);workbook=output.toByteArray();
        }
        assertEquals(2,service.process(jobId,new ByteArrayInputStream(workbook)));
        assertEquals(physical.id,staged.physicalAssetId);assertEquals(product.id,staged.productAssetId);assertEquals("/api/v1/assets/"+product.id,staged.payload.path("image").asText());
    }

    private static void addPicture(XSSFWorkbook workbook,org.apache.poi.ss.usermodel.Drawing<?> drawing,byte[] bytes,int column){var anchor=workbook.getCreationHelper().createClientAnchor();anchor.setCol1(column);anchor.setRow1(1);anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);drawing.createPicture(anchor,workbook.addPicture(bytes,XSSFWorkbook.PICTURE_TYPE_PNG));}
}

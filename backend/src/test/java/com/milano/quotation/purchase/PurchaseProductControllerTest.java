package com.milano.quotation.purchase;

import com.milano.quotation.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseProductControllerTest {
    private PurchaseProductService products; private AuditService audit; private PurchaseProductController controller;

    @BeforeEach void setup(){products=mock(PurchaseProductService.class);audit=mock(AuditService.class);controller=new PurchaseProductController(products,audit);}
    @Test void pastedCreationUsesDedicatedValidationAndAudit() {
        var rows=java.util.List.<tools.jackson.databind.JsonNode>of(JsonNodeFactory.instance.objectNode().put("sku","P-1"));
        when(products.createPasted(rows)).thenReturn(rows);
        controller.paste(rows);
        verify(products).createPasted(rows);
        verify(audit).record(eq("purchase.paste-create"),eq("purchase-product"),eq("batch"),eq("success"),argThat(detail->detail.get("count").equals(1)));
        verify(products,never()).upsertAll(any());
    }
    @Test void rejectsArrayInputBeforeCastingAndWriting() {
        assertThrows(com.milano.quotation.common.AppException.class,()->controller.upsert("QA-INVALID",JsonNodeFactory.instance.arrayNode()));
        verifyNoInteractions(products);
    }

    @Test void auditsCatalogStateSuccessAndFailure(){
        var input=new PurchaseProductController.CatalogStateInput("disabled",4);
        when(products.changeCatalogState("SAFE-1","disabled",4)).thenReturn(JsonNodeFactory.instance.objectNode().put("sku","SAFE-1"));
        controller.catalogState("SAFE-1",input);
        verify(audit).record(eq("purchase.disable"),eq("purchase-product"),eq("SAFE-1"),eq("success"),argThat(detail->detail.get("expectedVersion").equals(4L)));

        reset(audit);when(products.changeCatalogState("SAFE-1","disabled",4)).thenThrow(com.milano.quotation.common.AppException.conflict("版本冲突"));
        assertThrows(com.milano.quotation.common.AppException.class,()->controller.catalogState("SAFE-1",input));
        verify(audit).record(eq("purchase.catalog-state"),eq("purchase-product"),eq("SAFE-1"),eq("failure"),argThat(detail->detail.get("reason").equals("版本冲突")));
    }

    @Test void auditsBlockedDeleteWithReferenceCounts(){
        var check=new PurchaseProductDeletionGuard.DeletionCheck(false,9,2,3,4,5,1);
        when(products.delete("SAFE-2",9)).thenThrow(new PurchaseProductService.DeletionBlocked(check,"存在引用"));
        assertThrows(PurchaseProductService.DeletionBlocked.class,()->controller.delete("SAFE-2",9));
        verify(audit).record(eq("purchase.delete"),eq("purchase-product"),eq("SAFE-2"),eq("failure"),argThat(detail->detail.get("quotationRecords").equals(3)));
    }
}

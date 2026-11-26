package com.milano.quotation.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BrazilCustomerGradeTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void validatesAndPreservesTheIndependentGradeAcrossOldClientSaves() {
        var before=mapper.readTree("[{\"grade\":\"S\",\"coefficient\":1.12,\"enabled\":true},{\"grade\":\"ALIEXPRESS_BR\",\"coefficient\":1.34,\"enabled\":true}]");
        assertDoesNotThrow(()->FinanceSettingValidation.validate("customer-grades",before));
        var old=mapper.readTree("[{\"grade\":\"S\",\"coefficient\":1.15,\"enabled\":true}]");
        var saved=FinanceSettingController.preserveBrazilGrade(before,old);
        assertEquals(2,saved.size());assertEquals(before.get(1),saved.get(1));assertEquals(1.15,saved.get(0).path("coefficient").asDouble());
        assertEquals(1,old.size());assertEquals(1.12,before.get(0).path("coefficient").asDouble());
        var update=before.deepCopy();((tools.jackson.databind.node.ObjectNode)update.get(1)).put("coefficient",1.36).put("enabled",false);
        assertEquals(update,FinanceSettingController.preserveBrazilGrade(before,update));
        var wrapped=mapper.createObjectNode().set("grades",old);
        assertEquals(2,FinanceSettingController.preserveBrazilGrade(before,wrapped).path("grades").size());
    }
}

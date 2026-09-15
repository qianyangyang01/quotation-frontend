package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class CustomerOperationFeesTest {
    final JsonMapper mapper = new JsonMapper();
    ObjectNode settings() { return (ObjectNode) mapper.readTree("""
        {"customers":[{"id":"one","name":"客户甲","feeUsd":1.25,"enabled":true}]}
        """); }
    ObjectNode quote() { return (ObjectNode) mapper.readTree("""
        {"customerName":"客户甲","customerOperation":{"id":"one","name":"客户甲","feeUsd":1.25}}
        """); }
    @Test void requiresExplicitSelectionAndRejectsStaleChangesWithoutMutatingHistory() {
        var settings=settings(); var quote=quote(); var original=quote.deepCopy();
        assertDoesNotThrow(()->CustomerOperationFees.validate(settings,quote));
        var free=quote.deepCopy();free.remove("customerOperation");
        assertDoesNotThrow(()->CustomerOperationFees.validate(settings,free));
        for (var change : new String[]{"{\"feeUsd\":2}","{\"name\":\"客户乙\"}","{\"enabled\":false}"}) {
            var edited=settings(); ((ObjectNode)edited.path("customers").get(0)).setAll((ObjectNode)mapper.readTree(change));
            assertThrows(AppException.class,()->CustomerOperationFees.validate(edited,quote));
        }
        assertThrows(AppException.class,()->CustomerOperationFees.validate(mapper.readTree("{}"),quote));
        assertEquals(original,quote);
    }
    @Test void validatesAmountsNamesAndBooleans() {
        assertDoesNotThrow(()->FinanceSettingValidation.validate("customer-operation-fees",settings()));
        for (var amount : new String[]{"-1","0.001","1000001","null","\"1\""}) {
            var settings=settings(); ((ObjectNode)settings.path("customers").get(0)).set("feeUsd",mapper.readTree(amount));
            assertThrows(AppException.class,()->FinanceSettingValidation.validate("customer-operation-fees",settings));
        }
        var duplicate=settings(); ((tools.jackson.databind.node.ArrayNode)duplicate.path("customers")).add(mapper.readTree("{\"id\":\"two\",\"name\":\" 客户甲 \",\"feeUsd\":0,\"enabled\":true}"));
        assertThrows(AppException.class,()->FinanceSettingValidation.validate("customer-operation-fees",duplicate));
        var invalid=quote();invalid.put("customerOperation","bad");
        assertThrows(AppException.class,()->CustomerOperationFees.validate(settings(),invalid));
    }
}

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

    ObjectNode tierCustomer() { return (ObjectNode) mapper.readTree("""
        {"id":"one","name":"客户甲","enabled":true,"feeUsd":0.3,"feesByQuantityUsd":{"1":0.3,"2":0.5,"3":0.7,"above3":0.8}}
        """); }
    ObjectNode tierSettings() { var result=settings(); ((tools.jackson.databind.node.ArrayNode)result.path("customers")).set(0,tierCustomer()); return result; }
    ObjectNode tierQuote() { var result=quote(); result.set("customerOperation",tierCustomer()); return result; }
    @Test void validatesEveryTierAndRejectsPartialSnapshots() {
        assertDoesNotThrow(()->FinanceSettingValidation.validate("customer-operation-fees",tierSettings()));
        assertDoesNotThrow(()->CustomerOperationFees.validate(tierSettings(),tierQuote()));
        for(var key: new String[]{"1","2","3","above3"}) {
            for(var value: new String[]{"-1","0.001","1000001","null","\"\"","\"0.5\""}) {
                var settings=tierSettings();
                ((ObjectNode)settings.path("customers").get(0).path("feesByQuantityUsd")).set(key,mapper.readTree(value));
                assertThrows(AppException.class,()->FinanceSettingValidation.validate("customer-operation-fees",settings));
            }
            var partial=tierQuote(); ((ObjectNode)partial.path("customerOperation").path("feesByQuantityUsd")).remove(key);
            assertThrows(AppException.class,()->CustomerOperationFees.validate(tierSettings(),partial));
            var changed=tierSettings(); ((ObjectNode)changed.path("customers").get(0).path("feesByQuantityUsd")).put(key,9);
            assertThrows(AppException.class,()->CustomerOperationFees.validate(changed,tierQuote()));
        }
    }
    @Test void legacyClientCannotSaveUnequalTiersOrFlattenFinanceConfiguration() {
        var legacy=quote(); ((ObjectNode)legacy.path("customerOperation")).put("feeUsd",0.3);
        assertThrows(AppException.class,()->CustomerOperationFees.validate(tierSettings(),legacy));
        assertThrows(AppException.class,()->CustomerOperationFees.preventLegacyOverwrite(tierSettings(),settings()));
        assertDoesNotThrow(()->CustomerOperationFees.preventLegacyOverwrite(tierSettings(),tierSettings()));
        var uniform=tierSettings(); var row=(ObjectNode)uniform.path("customers").get(0); row.put("feeUsd",1.25);
        for(var key: new String[]{"1","2","3","above3"}) ((ObjectNode)row.path("feesByQuantityUsd")).put(key,1.25);
        assertDoesNotThrow(()->CustomerOperationFees.validate(uniform,quote()));
        assertDoesNotThrow(()->CustomerOperationFees.preventLegacyOverwrite(uniform,settings()));
        var snapshot=quote(); ((ObjectNode)snapshot.path("customerOperation")).set("feesByQuantityUsd",row.path("feesByQuantityUsd").deepCopy());
        assertDoesNotThrow(()->CustomerOperationFees.validate(settings(),snapshot));
        var before=snapshot.deepCopy(); CustomerOperationFees.validate(uniform,snapshot); assertEquals(before,snapshot);
    }
}

package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real GET-only check. Never runs in CI or writes a procurement database. */
@EnabledIfEnvironmentVariable(named="SHIMO_READONLY_ENABLED",matches="true")
class ShimoClientReadOnlySmokeTest {
    @Test void readsAllThirteenApprovedSheetsUsingProductionAdapter() throws Exception {
        var mapper=JsonMapper.builder().build();
        var sheets="张汝玉Raul,张汝玉Calin女装,张汝玉Fem女装,乔月,陈晨,陈晨BK,陈晨枕头,刘玉宏,孙丽红,陶春燕,汪晶,业务新人,老数据更新";
        var client=new ShimoClient(System.getenv("SHIMO_READONLY_TOKEN").trim(),sheets,mapper);
        var cache=new ShimoClient.PageCache() {
            public void prepare(String sheet,JsonNode index) {}
            public JsonNode read(String sheet,int first,int last) {return null;}
            public void write(String sheet,int first,int last,JsonNode rows) {}
        };
        long start=System.nanoTime();var all=client.readAll(cache);var preferred=ShimoSyncService.selectPreferred(all);
        int valid=0;var reasons=new TreeMap<String,Integer>();
        for(var row:preferred) {
            try {var patch=ShimoRowMapper.patch(row.cells());PurchasePasteValidator.validateValues(patch,1,false);valid++;}
            catch(RuntimeException e) {reasons.merge(e.getMessage(),1,Integer::sum);}
        }
        var result=Map.of("sourceRows",all.size(),"preferredRows",preferred.size(),"passesNewRecordValidation",valid,"pendingReasons",reasons,"seconds",(System.nanoTime()-start)/1e9,"sheets",client.sheets());
        Files.writeString(Path.of("target/shimo-readonly-smoke.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        assertEquals(13,client.sheets().size());assertTrue(all.size()>3000);assertTrue(valid>0);
        System.out.println("SHIMO_READONLY_SUMMARY="+mapper.writeValueAsString(result));
    }
}

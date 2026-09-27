package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShimoClientTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private String sku(int i) {return "AB"+String.format("%05d",i);}
    private ShimoClient client(int count) {
        var client=spy(new ShimoClient("test-only","业务新人,国际站,国际站批发报价",mapper));
        var index=mapper.createArrayNode();
        for(int i=0;i<count;i++) index.addArray().add(sku(i));
        doReturn(mapper.createArrayNode().add(mapper.valueToTree(Arrays.asList(ShimoRowMapper.HEADERS)))).when(client).values("业务新人","D1:AI1");
        doReturn(index).when(client).values("业务新人","G2:G5001");
        return client;
    }
    private ArrayNode rows(int first,int end) {
        var rows=mapper.createArrayNode();
        for(int i=first;i<end;i++) rows.add(ShimoRowMapperTest.cells(sku(i)));
        return rows;
    }
    @Test void knownSkuBatchesAreNotFetchedAndExcludedSheetsAreNeverRead() {
        var client=client(301);var cache=mock(ShimoClient.PageCache.class);
        doReturn(rows(300,301)).when(client).values("业务新人","D302:AI302");
        var result=client.readAll(cache,sku(300)::equals);
        assertEquals(List.of("业务新人"),client.sheets());assertEquals(1,result.size());
        assertEquals(sku(300),result.getFirst().sku());
        verify(client,never()).values("业务新人","D2:AI151");
        verify(client,never()).values("业务新人","D152:AI301");
        verify(client).values("业务新人","D302:AI302");
    }
    @Test void retryUsesSavedBatchAndContinuesWithFailedBatch() {
        var client=client(151);var pages=new HashMap<Integer,JsonNode>();
        var cache=new ShimoClient.PageCache() {
            public void prepare(String sheet,JsonNode index) {}
            public JsonNode read(String sheet,int first,int last) {return pages.get(first);}
            public void write(String sheet,int first,int last,JsonNode rows) {pages.put(first,rows);}
        };
        doReturn(rows(0,150)).when(client).values("业务新人","D2:AI151");
        doThrow(new IllegalStateException("network interrupted")).doReturn(rows(150,151)).when(client).values("业务新人","D152:AI152");
        assertThrows(IllegalStateException.class,()->client.readAll(cache));
        assertEquals(151,client.readAll(cache).size());
        verify(client,times(1)).values("业务新人","D2:AI151");
        verify(client,times(2)).values("业务新人","D152:AI152");
    }
    @Test void changedRowPositionsNeverEnterSuccessfulPageCache() {
        var client=client(1);var cache=mock(ShimoClient.PageCache.class);
        doReturn(rows(1,2)).when(client).values("业务新人","D2:AI2");
        assertThrows(IllegalStateException.class,()->client.readAll(cache));
        verify(cache,never()).write(anyString(),anyInt(),anyInt(),any());
    }
}

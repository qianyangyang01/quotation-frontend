package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class ShimoRowMapperTest {
    static ArrayNode cells(String sku) {
        var row=JsonNodeFactory.instance.arrayNode();
        for(int i=0;i<32;i++) row.addNull();
        row.set(0,JsonNodeFactory.instance.textNode("2026.9.5"));row.set(1,JsonNodeFactory.instance.textNode("采购员"));row.set(3,JsonNodeFactory.instance.textNode(sku));
        row.set(4,IntNode.valueOf(130));row.set(11,IntNode.valueOf(1));row.set(12,IntNode.valueOf(20));
        row.set(20,JsonNodeFactory.instance.textNode("是"));row.set(22,JsonNodeFactory.instance.textNode("8%"));
        return row;
    }
    @Test void requiredSourceValuesAndExplicitZero() {
        var row=cells("ABC123");row.set(22,NullNode.instance);
        assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
        row.set(22,JsonNodeFactory.instance.textNode("0%"));
        assertEquals(0,ShimoRowMapper.patch(row).path("taxPoint").asDouble());
        row.set(4,NullNode.instance);assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
        row.set(4,IntNode.valueOf(0));assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
    }
    @Test void conversionsMatchPasteAndBlankFieldsAreOmitted() {
        var row=cells(" ab c123 ");row.set(25,JsonNodeFactory.instance.textNode("有"));
        var p=ShimoRowMapper.patch(row);
        assertEquals("ABC123",p.path("sku").asText());assertEquals("2026-09-05",p.path("quotationDate").asText());
        assertEquals(.08,p.path("taxPoint").asDouble());assertEquals("有货",p.path("stockStatus").asText());
        assertFalse(p.has("notes"));assertFalse(p.has("productImage"));
        row.set(22,IntNode.valueOf(8));assertEquals(.08,ShimoRowMapper.patch(row).path("taxPoint").asDouble());
        row.set(22,DoubleNode.valueOf(.08));assertEquals(.08,ShimoRowMapper.patch(row).path("taxPoint").asDouble());
        row.set(22,JsonNodeFactory.instance.textNode("101%"));assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
        row.set(22,JsonNodeFactory.instance.textNode("-1%"));assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
    }
    @Test void rejectChangedSchemaAndImpossibleDate() {
        var header=JsonNodeFactory.instance.arrayNode();for(var s:ShimoRowMapper.HEADERS) header.add(s+"*");
        assertDoesNotThrow(()->ShimoRowMapper.validateHeader(header));
        header.set(3,JsonNodeFactory.instance.textNode("备注"));assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.validateHeader(header));
        var row=cells("ABC123");row.set(0,JsonNodeFactory.instance.textNode("2026.2.30"));assertThrows(IllegalArgumentException.class,()->ShimoRowMapper.patch(row));
    }
}

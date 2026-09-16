package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsSfDraftRepairTest {
    static final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSfDraftRepair repair=new LogisticsSfDraftRepair(null,mapper,null,new LogisticsParserAliases());

    @Test void clearsSixStaleIssuesAndOnlyTheirBlockersWhilePreservingPricesAndEvidence()throws Exception {
        try(var book=workbook()) {
            var payload=draft();var original=payload.deepCopy();
            ((ObjectNode)payload.path("rows").get(0)).put("blockingReason",payload.path("rows").get(0).path("blockingReason").asText()+"；最小计费重量超出重量段上限");
            payload.withArray("issues").addObject().put("row",99).put("sourceSheet","其他表").put("field","价格").put("message","未解析价格").put("level","error");
            var audit=repair.repair(payload,book);
            assertEquals(2,audit.size());assertEquals(1,payload.path("issues").size());
            assertEquals("未解析价格",payload.path("issues").get(0).path("message").asText());
            for(int i=0;i<2;i++)for(var field:new String[]{"pricePerKg","registrationFee","minChargeWeightKg","weightFromKg","weightToKg","rawValues","sourceDiscount","sourceOriginalRate"})
                assertEquals(original.path("rows").get(i).path(field),payload.path("rows").get(i).path(field),field);
            assertEquals("最小计费重量超出重量段上限",payload.path("rows").get(0).path("blockingReason").asText());
            assertEquals("",payload.path("rows").get(1).path("blockingReason").asText());
            assertTrue(repair.repair(payload,book).isEmpty());
        }
    }

    @Test void preservesInvalidDiscountsFormulasUnrecognizedColumnsAndNonSfRows()throws Exception {
        for(String invalid:new String[]{"zero","text","formula","settlement","unknown-header","other-provider","invalid-price","wrong-sheet"})try(var book=workbook()) {
            var payload=draft();var s=book.getSheetAt(0);
            for(int row:new int[]{20,21}) {
                if(invalid.equals("zero"))s.getRow(row).getCell(7).setCellValue(0);
                if(invalid.equals("text"))s.getRow(row).getCell(7).setCellValue("待确认");
                if(invalid.equals("formula"))s.getRow(row).getCell(7).setCellFormula("\"\"");
                if(invalid.equals("settlement"))s.getRow(row).getCell(8).setCellValue(40);
            }
            if(invalid.equals("unknown-header"))s.getRow(2).getCell(8).setCellValue("未知折后列");
            if(invalid.equals("other-provider"))payload.put("providerName","燕文");
            if(invalid.equals("invalid-price"))for(var row:payload.path("rows"))((ObjectNode)row).put("pricePerKg",0);
            if(invalid.equals("wrong-sheet"))book.setSheetName(0,"其他表");
            var before=payload.deepCopy();
            assertTrue(repair.repair(payload,book).isEmpty(),invalid);
            assertEquals(before,payload,invalid);
        }
    }

    @Test void readsOnlyRelevantSheetsAndPreservesFormulaBlocks()throws Exception {
        for(boolean formula:new boolean[]{false,true})try(var book=workbook();var output=new java.io.ByteArrayOutputStream()) {
            var payload=draft();
            var reference=book.createSheet("邮编参考");
            reference.createRow(0).createCell(0).setCellValue("unrelated source evidence");
            if(formula)for(int row:new int[]{20,21})book.getSheetAt(0).getRow(row).getCell(7).setCellFormula("\"\"");
            book.write(output);
            var audit=repair.repair(payload,output.toByteArray(),"source.xlsx");
            assertEquals(formula?0:2,audit.size());
            assertEquals(formula?6:0,payload.path("issues").size());
        }
    }

    static XSSFWorkbook workbook() {
        var book=new XSSFWorkbook();var s=book.createSheet("服装专线");var header=s.createRow(2);
        header.createCell(6).setCellValue("运费/kg");header.createCell(7).setCellValue("折扣率");header.createCell(8).setCellValue("折扣后运费");
        for(int source:new int[]{21,22}){var row=s.createRow(source-1);row.createCell(6).setCellValue(source==21?72:76);row.createCell(7);row.createCell(8);}
        return book;
    }
    static ObjectNode draft() {
        var payload=mapper.createObjectNode().put("providerName","顺丰").put("templateStatus","known").put("errors",6);
        var rows=payload.putArray("rows");var issues=payload.putArray("issues");
        for(int source:new int[]{21,22}) {
            var row=rows.addObject().put("rowKey","es-"+source).put("sourceSheet","服装专线").put("sourceRow",source)
                    .put("countryCode","ES").put("areaName","西班牙").put("sourceOriginalRateCell","G"+source)
                    .put("sourceOriginalRate",source==21?"72.0":"76.0").put("sourceDiscount","").put("sourceDiscountCell","H"+source)
                    .put("sourcePricingBasis","original-times-discount").put("pricingModel","per-kg").put("pricePerKg",source==21?72:76)
                    .put("registrationFee",18).put("currency","CNY").put("weightFromKg",source==21?.001:1.001).put("weightToKg",source==21?1:30)
                    .put("weightFromInclusive",true).put("weightToInclusive",true).put("minChargeWeightKg",.5).put("etaMinDays",6).put("etaMaxDays",10);
            row.putObject("rawValues").put("G"+source,source==21?"72.0":"76.0");
            for(var field:new String[]{"pendingReason","blockingReason"})row.put(field,"顺丰有效运费缺失或无效，禁止回退原价或按零计价；公斤价计费结构不完整");
            for(var message:new String[]{"顺丰折扣不是明确的有效折扣（须为0到1之间的系数、百分比或几折）","顺丰有效运费必须大于0","计费方式与基础价格字段不一致"})
                issues.addObject().put("row",source).put("sourceSheet","服装专线").put("field",message.startsWith("计费方式")?"计费方式":"pricePerKg").put("message",message).put("level","error");
        }
        return payload;
    }
}

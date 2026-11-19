package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MutableProductIdentityTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    byte[] change(byte[] data,String old,String code)throws Exception {
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(data))){
            for(var sheet:book)for(var row:sheet)for(var cell:row) {
                if(cell.getCellType()==CellType.STRING&&cell.getStringCellValue().contains(old))cell.setCellValue(cell.getStringCellValue().replace(old,code));
                else if(cell.getCellType()==CellType.NUMERIC&&Long.toString((long)cell.getNumericCellValue()).equals(old))cell.setCellValue(code);
            }
            return LogisticsSourceParserTest.bytes(book);
        }
    }
    void equivalent(String provider,byte[] source,CompanyChannelScope scope,String old,String code)throws Exception {
        var before=parser.parse(source,provider+".xlsx",scope);
        var changed=change(source,old,code);var after=parser.parse(changed,provider+".xlsx",scope);
        assertFalse(before.path("channels").isEmpty(),provider+before.toPrettyString());
        assertEquals(before.path("channels").size(),after.path("channels").size(),provider+after.toPrettyString());
        boolean actualCode=false;
        for(var a:before.path("channels")) {
            var b=after.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(a.path("companyChannelId"))).findFirst().orElseThrow();
            assertEquals(0,b.path("errors").asInt(),provider+b.toPrettyString());
            assertEquals(a.path("quoteReady"),b.path("quoteReady"),provider+b.toPrettyString());
            assertEquals(a.path("rows").size(),b.path("rows").size(),provider);
            for(int i=0;i<a.path("rows").size();i++) {
                var x=a.path("rows").get(i);var y=b.path("rows").get(i);
                for(var key:List.of("countryCode","zoneName","pricePerKg","registrationFee","weightFromKg","weightToKg","minChargeWeightKg","pricingModel","billingStepKg","billingStepBands","intervalPrice","allowedMarks"))
                    assertEquals(x.path(key),y.path(key),provider+" "+key);
                actualCode|=y.path("sourceProductCode").asText().contains(code);
            }
        }
        assertTrue(actualCode,provider+" must retain actual updated code");
        var output=System.getProperty("mutable.probes","");
        if(!output.isBlank()) {
            var dir=Path.of(output,provider+"-"+code+"-"+source.length);Files.createDirectories(dir);
            Files.write(dir.resolve("source.xlsx"),changed);
            Files.writeString(dir.resolve("scope.json"),scope.snapshot().toPrettyString());
            Files.writeString(dir.resolve("expected.json"),after.toPrettyString());
        }
    }
    @Test void carrierCodeUpdatesKeepChannelAndEveryPriceRow()throws Exception {
        var bz=new BaizhouSourceTest();equivalent("百州",bz.fixture(),bz.scope(),"BZ503","BZ2027");
        var yun=new YunexpressAdditionalTest();equivalent("云途",yun.fixture(98,.05,"BKZXR","1",false),yun.directory(),"BKZXR","BK2027");
        var yan=new YanwenWanbangAdditionalTest();equivalent("燕文",yan.fixture(50,"0.03","1667",false),yan.directory(),"1667","9667");
        var wb=new WanbangMediumSourceTest();equivalent("万邦",wb.fixture(true,"",80),wb.directory(),"WBSLMPPH","WBNEWPH");
        var preferred=new WanbangPreferredSourceTest();equivalent("万邦",preferred.fixture(true,"",0),preferred.directory(),"EUSLPH-P","NEWPH-P");
        var clothing=new WanbangClothingSourceTest();equivalent("万邦",clothing.helper.fixture(true,"",0),clothing.directory(),"EUSLPHFZ-P","NEWFZ-P");
        var jy=new JieyitongdaSourceParserTest();equivalent("捷易通达",jy.fixture(true,""),jy.directory(),"1961","9961");
        var jt=new JitongPureBatteryTest();equivalent("极通环球",jt.fixture(95,"JT-HQ-MDCD"),jt.directory(),"JT-HQ-MDCD","NEW-BATTERY-2027");
        var qj=new QiaojieSourceParserTest();equivalent("巧捷",qj.fixture("巧捷小包全球商派E(服装)QEGBFE",65,false),qj.directory(),"QEGBFE","QENEW2027");
        var js=new JisuSourceParserTest();equivalent("急速国际",js.fixture(63,1),js.directory(),JisuSourceRules.CODE,"JS2027-NEW");
    }
    @Test void qiaojieSeparateCodeLabelAndCountryRestrictionSurviveCodeUpdates()throws Exception {
        var qj=new QiaojieSourceParserTest();byte[] source;
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(qj.fixture("巧捷小包全球商派E(服装)",65,false)))) {
            LogisticsSourceParserTest.row(book.getSheetAt(0),3,"产品代码：QEGBFE");source=LogisticsSourceParserTest.bytes(book);
        }
        equivalent("巧捷",source,qj.directory(),"QEGBFE","QENEW2027");
        assertTrue(QiaojieSourceRules.usOnly("巧捷小包全球特惠A(服装)QENEWA","QENEWA",""));
        assertEquals("NEW-BATTERY-2027",JitongPureBatteryRules.code("国家\nNEW-BATTERY-2027\n重量段"));
        assertEquals("NEW2027",JitongPureBatteryRules.code("渠道代码：NEW2027\n国家"));
        assertTrue(LogisticsProductIdentity.nameAttributeConflict("特快化妆品","特快普货"));
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="mutable.realRoot",matches=".+")
    void realWorkbooksRetainIdentityAndAllPricesAfterCodeUpdates()throws Exception {
        var root=Path.of(System.getProperty("mutable.realRoot"));
        equivalent("巧捷",Files.readAllBytes(root.resolve("物流更新表/巧捷专线小包价-20260921(5)1.xlsx")),new QiaojieSourceParserTest().directory(),"QEUBF","QENEW2027");
        equivalent("极通环球",Files.readAllBytes(root.resolve("物流更新表/更新表/极通环球供应链（小包）报价表 2026-9-21.xlsx")),new JitongPureBatteryTest().directory(),"JT-HQ-MDCD","NEW-BATTERY-2027");
        equivalent("急速国际",Files.readAllBytes(root.resolve("物流更新表/JS-合肥米莱诺0825-新(1).xlsx")),new JisuSourceParserTest().directory(),JisuSourceRules.CODE,"JS2027-NEW");
    }
    @Test void rongdingRetainsAcPairChecksButAcceptsUpdatedCodesForRegisteredNames()throws Exception {
        var rd=new RongdingProductCellTest();var scope=rd.snapshot();
        for(int i=0;i<scope.path("entries").size();i++)((ObjectNode)scope.path("entries").get(i)).putArray("aliases").add(rd.name(i).split("\n")[0]);
        equivalent("容鼎",rd.fixture(),new CompanyChannelScope(scope),"PK00186","RDNEW2027");
        var match=new CompanyChannelScope(scope).matchSourceCell("容鼎",rd.name(0),"RDNEW2027(B)\nPK00208(C)");
        assertEquals("ambiguous",match.status());
        ((ObjectNode)scope.path("entries").get(0)).putArray("aliases").add(rd.name(0));
        assertTrue(new CompanyChannelScope(scope).matchSourceCell("容鼎",rd.name(0),"RDNEW2027(A)\nRDNEW2028(C)").accepted());
    }
    @Test void sunyouReadsAChangedExplicitCodeInsteadOfWritingACatalogueConstant()throws Exception {
        var sy=new SunyouSourceTest();byte[] source;
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(sy.fixture("0.226",false,false,"")))){
            LogisticsSourceParserTest.row(book.getSheetAt(0),3,"产品代码：SSBRAM");source=LogisticsSourceParserTest.bytes(book);
        }
        equivalent("顺友",source,sy.directory(),"SSBRAM","SYNEW2027");
    }
    @Test void changedAttributeAndKnownOtherProductRemainBlocked()throws Exception {
        var f=new FanzhirunSourceTest();
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(f.fixture()))){
            book.getSheetAt(0).getRow(3).getCell(2).setCellValue("带电");
            assertFalse(f.parse(LogisticsSourceParserTest.bytes(book)).path("quoteReady").asBoolean());
        }
        var bz=new BaizhouSourceTest();assertFalse(bz.parse(change(bz.fixture(),"BZ503","BZ504")).path("quoteReady").asBoolean());
        var jy=new JieyitongdaSourceParserTest();var c=jy.channel(parser.parse(jy.fixture(true,"code"),"捷易通达.xlsx",jy.directory()),"以色列敏感");assertFalse(c.path("quoteReady").asBoolean());
        assertTrue(LogisticsProductIdentity.attributeConflict("美国普货专线","带电"));
        assertTrue(LogisticsProductIdentity.attributeConflict("美国化妆品专线","普货"));
        assertFalse(LogisticsProductIdentity.attributeConflict("服装普货","服装"));
    }
    @Test void unchangedCodeDoesNotOverrideConflictingExplicitAttribute()throws Exception {
        var helper=new WanbangMediumSourceTest();
        try(var book=WorkbookFactory.create(new ByteArrayInputStream(helper.fixture(false,"",80)))) {
            for(var sheet:book) {
                int header=-1;for(var row:sheet)for(var cell:row)if(cell.getCellType()==CellType.STRING&&cell.getStringCellValue().equals("产品代码"))header=row.getRowNum();
                if(header<0)continue;
                int col=sheet.getRow(header).getLastCellNum();sheet.getRow(header).createCell(col).setCellValue("货物属性");
                for(int r=header+1;r<=sheet.getLastRowNum();r++)if(sheet.getRow(r)!=null)sheet.getRow(r).createCell(col).setCellValue("化妆品");
            }
            var result=parser.parse(LogisticsSourceParserTest.bytes(book),"万邦.xlsx",helper.directory());
            assertFalse(result.path("channels").isEmpty());for(var c:result.path("channels"))assertFalse(c.path("quoteReady").asBoolean(),c.toPrettyString());
        }
    }
}

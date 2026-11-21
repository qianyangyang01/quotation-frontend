package com.milano.quotation.logistics;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import static com.milano.quotation.logistics.LogisticsSourceParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

class YanwenJapanFirstNextTest {
    final ObjectMapper mapper=new ObjectMapper();
    final LogisticsSourceParser parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    final LogisticsBillingEngine engine=new LogisticsBillingEngine(mapper);
    static final String[] NAMES={"燕文专线快递-普货","燕文专线快递-特货","燕文专线追踪-普货","燕文专线追踪-特货"};
    static final String[] CODES={"440","557","481","484"};
    static final double[][] FIRST={{38.5},{43},{34,34.5,36.5},{35,35,36}};
    static final double[][] NEXT={{8.5},{16},{7.5,9.5,11.5},{12,13.5,14}};
    byte[] fixture(double firstKg,String country) throws Exception {
        try(var book=new XSSFWorkbook()) {
            for(int i=0;i<4;i++) {
                var s=book.createSheet(NAMES[i]);row(s,1,"", "", "",NAMES[i],"","","","","","产品编号："+CODES[i]+"\n生效日期：2026-11-23 09:00");
                row(s,3,"","大洲","国家","CountryCode","参考时效(工作日)","重量限制(KG)","重量段(KG)","最小计费重量(KG)","公斤运费(元/KG)","","处理费(元/件)");
                row(s,4,"","欧洲","法国","FR","5-8","0-20","0.001 - 20",.05,70,"",20);
                row(s,5,"","大洲","国家","CountryCode","参考时效(工作日)","重量限制(KG)","重量段(KG)","首重","首重价格","续重","续重价格","处理费");
                for(int j=0;j<FIRST[i].length;j++)row(s,6+j,"","亚洲",country,country.equals("日本")?"JP":"US",i==0?"2-3":"3-5","0-20",i<2?"0.001 - 10":j==0?"0.001 - 2":j==1?"2.001 - 10":"10.001 - 20",firstKg,FIRST[i][j],.5,NEXT[i][j],0);
                row(s,11,"","大洲","国家","CountryCode","参考时效(工作日)","重量限制(KG)","重量段(KG)","最小计费重量(KG)","公斤运费(元/KG)","","处理费(元/件)");
                row(s,12,"","欧洲","德国","DE","5-8","0-20","0.001 - 20",.05,80,"",21);
            }
            return bytes(book);
        }
    }
    CompanyChannelScope scope() {
        var config=mapper.createObjectNode().put("enabled",true);var entries=config.putArray("entries");
        for(int i=0;i<4;i++)entries.addObject().put("id",UUID.nameUUIDFromBytes(NAMES[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).put("providerName","燕文").put("channelName",NAMES[i]).put("logisticsAttribute",i%2==0?"普货":"带电").putArray("productCodes").add(CODES[i]);
        return new CompanyChannelScope(config);
    }
    void verify(JsonNode parsed,boolean real) {
        assertEquals(4,parsed.path("channels").size());
        int sourceRows=0,expanded=0;
        for(var channel:parsed.path("channels")) {
            int i=List.of(NAMES).indexOf(channel.path("channelName").asText());assertTrue(i>=0);
            assertEquals(0,channel.path("errors").asInt(),channel.path("issues").toString());
            assertTrue(channel.path("pricingReady").asBoolean(),channel.path("blockingReasons").toString());
            var jp=mapper.createArrayNode();var refs=new HashSet<Integer>();
            for(var r:channel.path("rows"))if(r.path("countryCode").asText().equals("JP")){jp.add(r);refs.add(r.path("sourceRow").asInt());assertEquals("first-next",r.path("sourcePricingModel").asText());}
            assertEquals(i<2?20:40,jp.size());assertEquals(FIRST[i].length,refs.size());sourceRows+=refs.size();expanded+=jp.size();
            assertTrue(engine.unsupported(jp).isEmpty(),engine.unsupported(jp).toString());
            for(int gram=1;gram<=(i<2?10000:20000);gram+=37)check(i,jp,gram/1000.0);
            for(double w:new double[]{.000001,.499999,.5,.500001,1,1.999999,2,2.000001,2.001,9.999999,10})check(i,jp,w);
            if(i>=2)for(double w:new double[]{10.000001,10.001,19.999999,20})check(i,jp,w);
            var max=i<2?10:20;
            assertThrows(RuntimeException.class,()->engine.calculate(jp,mapper.createObjectNode().put("country","JP").put("weightKg",max+.000001)));
            assertThrows(RuntimeException.class,()->engine.calculate(jp,mapper.createObjectNode().put("country","JP").put("weightKg",0)));
            if(!real)assertEquals(2,channel.path("rows").size()-jp.size());
        }
        assertEquals(8,sourceRows);assertEquals(120,expanded);
    }
    void check(int i,JsonNode rows,double weight) {
        double charged=Math.ceil(weight*2)/2;int band=i<2?0:charged<=2?0:charged<=10?1:2;
        double expected=FIRST[i][band]+(charged-.5)/.5*NEXT[i][band];
        var result=engine.calculate(rows,mapper.createObjectNode().put("country","日本").put("weightKg",weight));
        assertEquals(expected,result.path("total").asDouble(),1e-8,NAMES[i]+" "+weight);
    }
    @Test void eightSourceTariffsAreExactlyEquivalentAtEveryHalfKgBoundary() throws Exception {verify(parser.parse(fixture(.5,"日本"),"燕文.xlsx",scope()),false);}
    @Test void unknownFirstWeightBlocksSelectedChannelRatherThanGuessing() throws Exception {
        var p=parser.parse(fixture(.25,"日本"),"燕文.xlsx",scope());
        for(var c:p.path("channels")){assertTrue(c.path("errors").asInt()>0);assertFalse(c.path("pricingReady").asBoolean());}
    }
    @Test void otherCountriesStillUseTheExistingFirstNextFilter() throws Exception {
        var p=parser.parse(fixture(.5,"美国"),"燕文.xlsx",scope());
        for(var c:p.path("channels")){assertEquals(2,c.path("rows").size());assertEquals(0,c.path("errors").asInt());}
    }
    @Test void actualWorkbookIfSupplied() throws Exception {
        var file=System.getenv("YANWEN_JAPAN_SOURCE");if(file==null||file.isBlank())return;
        var p=parser.parse(Files.readAllBytes(Path.of(file)),Path.of(file).getFileName().toString(),scope());verify(p,true);
        var out=System.getenv("YANWEN_JAPAN_PARSED");if(out!=null)Files.writeString(Path.of(out),p.toPrettyString());
    }
}

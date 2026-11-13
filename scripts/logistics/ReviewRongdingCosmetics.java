package com.milano.quotation.logistics;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.math.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ReviewRongdingCosmetics {
    static final ObjectMapper M=new ObjectMapper();
    static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    static void reconcile(JsonNode channel,JsonNode expected){
        require(channel.path("errors").asInt()==0&&channel.path("pricingReady").asBoolean()&&channel.path("rows").size()==6,"Invalid channel: "+channel.path("issues"));
        require(channel.path("companyChannelId").equals(expected.path("companyChannelId")),"Wrong identity");
        for(int i=0;i<6;i++){
            var r=channel.path("rows").get(i);var e=expected.path("rows").get(i);
            for(var field:e.properties()) {
                var actual=r.path(field.getKey());var value=field.getValue();
                require(value.isNumber()?actual.isNumber()&&value.decimalValue().compareTo(actual.decimalValue())==0:value.equals(actual),"Mismatch "+field.getKey()+" row "+e.path("sourceRow"));
            }
            require(r.path("sourceProductCode").asText().startsWith(expected.path("code").asText()+"(A)"),"Wrong A product");
        }
    }
    static void billing(JsonNode channel,JsonNode cases){
        var engine=new LogisticsBillingEngine(M);
        for(var c:cases.path("samples")){
            var input=M.createObjectNode().put("country","US").set("weightKg",c.path("weightKg"));
            var actual=engine.calculate(channel.path("rows"),input);
            require(actual.path("total").decimalValue().compareTo(c.path("total").decimalValue())==0,"Billing mismatch "+c);
        }
        for(var c:cases.path("rejected")){
            boolean rejected=false;
            try{engine.calculate(channel.path("rows"),c);}catch(com.milano.quotation.common.AppException ex){rejected=true;}
            require(rejected,"Should reject "+c);
        }
    }
    public static void main(String[] args)throws Exception {
        var root=Path.of(args[0]);var bytes=Files.readAllBytes(root.resolve("source.xlsx"));
        var expected=M.readTree(Files.readString(root.resolve("expected.json")));
        require(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected.path("sha256").asText()),"Source changed");
        var scope=new CompanyChannelScope(M.readTree(Files.readString(root.resolve("company.json"))));
        var parser=new LogisticsSourceParser(M,new LogisticsWorkbookService(M));
        var parsed=parser.parse(bytes,expected.path("source").asText(),scope);
        Files.writeString(root.resolve("parsed.json"),parsed.toPrettyString());
        require(parsed.path("channels").size()==2&&parsed.path("ambiguousChannels").asInt()==0,"Wrong source selection");
        var cases=M.readTree(Files.readString(root.resolve("billing-cases.json")));
        int checked=0;
        for(int index=0;index<2;index++){
            var e=expected.path("channels").get(index);
            var ch=parsed.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(e.path("companyChannelId"))).findFirst().orElseThrow();
            reconcile(ch,e);billing(ch,cases.get(index));
            var engine=new LogisticsBillingEngine(M);
            for(int grams=1;grams<=5000;grams++){
                BigDecimal weight=BigDecimal.valueOf(grams,3),charge=weight.max(new BigDecimal("0.05"));
                var matches=e.path("rows").valueStream().filter(r->{int lo=charge.compareTo(r.path("weightFromKg").decimalValue()),hi=charge.compareTo(r.path("weightToKg").decimalValue());return (lo>0||lo==0&&r.path("weightFromInclusive").asBoolean())&&hi<=0;}).toList();
                require(matches.size()==1,"Independent coverage mismatch");var r=matches.getFirst();
                var amount=charge.multiply(r.path("pricePerKg").decimalValue()).add(r.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP);
                var actual=engine.calculate(ch.path("rows"),M.createObjectNode().put("country","US").put("weightKg",weight));
                require(amount.compareTo(actual.path("total").decimalValue())==0,"Gram price mismatch "+grams);checked++;
            }
        }
        int faults=0;
        for(int col:new int[]{3,4,5,2})try(var book=new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))){
            var sheet=book.getSheet(expected.path("channels").get(1).path("rows").get(0).path("sourceSheet").asText());
            sheet.getRow(col==2?27:32).getCell(col).setCellValue(col==2?"PK003690(A)\nPK00379(C)":"");
            var out=new java.io.ByteArrayOutputStream();book.write(out);
            var bad=parser.parse(out.toByteArray(),expected.path("source").asText(),scope);
            var target=bad.path("channels").valueStream().filter(c->c.path("companyChannelId").asText().equals("f1789e9c-769a-4f87-a751-42453252f1e1")).toList();
            require(target.isEmpty()||target.getFirst().path("errors").asInt()>0||!target.getFirst().path("pricingReady").asBoolean(),"Fault accepted column "+col);
            var old=bad.path("channels").valueStream().filter(c->c.path("companyChannelId").asText().equals("7242c2e4-ac63-3bbf-8b12-f9ef5cb869f3")).findFirst().orElseThrow();
            if(col!=2)reconcile(old,expected.path("channels").get(0));
            else require(!old.path("pricingReady").asBoolean(),"Conflicting sheet must block partial replacement");faults++;
        }
        Files.writeString(root.resolve("validation.json"),M.createObjectNode().put("passed",true).put("channels",2).put("priceRows",12).put("priceFields",24).put("gramCases",checked).put("faultCases",faults).toPrettyString());
        System.out.println("RONGDING_VALIDATED "+checked+" gram cases, "+faults+" fail-closed cases");
    }
}

package com.milano.quotation.logistics;
import tools.jackson.databind.*;
import java.nio.file.*;
import java.math.*;
import tools.jackson.databind.node.*;
public class ReviewSfBulky {
 static final ObjectMapper M=new ObjectMapper();
 static void require(boolean condition,String why){if(!condition)throw new IllegalStateException(why);}
 static void reconcile(JsonNode actual,JsonNode expected){
  require(actual.path("errors").asInt()==0&&actual.path("pricingReady").asBoolean()&&actual.path("rows").size()==52,"Invalid parsed channel");
  require(actual.path("companyChannelId").equals(expected.path("companyChannelId")),"Wrong identity");
  for(var r:expected.path("rows")){
   var matches=actual.path("rows").valueStream().filter(a->a.path("sourceRow").equals(r.path("sourceRow"))).toList();require(matches.size()==1,"Wrong row count");
   for(var field:r.properties()){var a=matches.getFirst().path(field.getKey());var e=field.getValue();require(e.isNumber()?a.isNumber()&&e.decimalValue().compareTo(a.decimalValue())==0:e.equals(a),"Mismatch "+field.getKey()+" row "+r.path("sourceRow"));}
  }
 }
 static void billing(JsonNode channel,JsonNode cases){
  var engine=new LogisticsBillingEngine(M);
  for(var c:cases.path("samples")){
   var actual=engine.calculate(channel.path("rows"),c);
   for(String f:new String[]{"total","chargeWeightKg"})require(actual.path(f).decimalValue().compareTo(c.path(f).decimalValue())==0,"Boundary mismatch "+c+" "+f);
  }
  for(var c:cases.path("rejected")){boolean rejected=false;try{engine.calculate(channel.path("rows"),c);}catch(com.milano.quotation.common.AppException ex){rejected=true;}require(rejected,"Should reject "+c);}
 }
 public static void main(String[] args)throws Exception {
  var p=Path.of(args[0]);var m=new ObjectMapper();var e=m.readTree(Files.readString(p.resolve("expected.json")));
  if(args.length==2){
   var old=new LogisticsSourceParser(m,new LogisticsWorkbookService(m)).parse(Files.readAllBytes(p.resolve("source.xlsx")),e.path("source").asText(),new CompanyChannelScope(m.readTree(Files.readString(p.resolve("existing-company.json")))));
   Files.writeString(p.resolve(args[1]+".json"),old.path("channels").toPrettyString());return;
  }
  var parsed=new LogisticsSourceParser(m,new LogisticsWorkbookService(m)).parse(Files.readAllBytes(p.resolve("source.xlsx")),e.path("source").asText(),new CompanyChannelScope(m.readTree(Files.readString(p.resolve("company.json")))));
  Files.writeString(p.resolve("parsed.json"),parsed.toPrettyString());
  require(parsed.path("channels").size()==1,"Wrong import scope");var channel=parsed.path("channels").get(0);reconcile(channel,e);
  var cases=m.readTree(Files.readString(p.resolve("billing-cases.json")));billing(channel,cases);
  int count=0;var engine=new LogisticsBillingEngine(m);
  for(var code:e.path("rows").valueStream().map(r->r.path("countryCode").asText()).distinct().toList()){
   var expected=e.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals(code)).toList();
   var actual=m.createArrayNode();channel.path("rows").valueStream().filter(r->r.path("countryCode").asText().equals(code)).forEach(actual::add);
   for(int g=1;g<=expected.getLast().path("weightToKg").asInt()*1000;g++){
    var w=BigDecimal.valueOf(g,3);var row=expected.get(w.compareTo(expected.getFirst().path("weightToKg").decimalValue())<=0?0:1);
    var total=w.multiply(row.path("pricePerKg").decimalValue()).add(row.path("registrationFee").decimalValue()).setScale(2,RoundingMode.HALF_UP);
    require(engine.calculate(actual,m.createObjectNode().put("country",code).put("weightKg",w)).path("total").decimalValue().compareTo(total)==0,"Gram mismatch "+code+g);count++;
   }
  }
  Files.writeString(p.resolve("validation.json"),m.createObjectNode().put("passed",true).put("rows",52).put("countries",26).put("gramCases",count).put("boundaryCases",cases.path("samples").size()).put("rejectedCases",cases.path("rejected").size()).toPrettyString());
  System.out.println("SF_BULKY_VALIDATED "+count+" gram cases");
 }
}

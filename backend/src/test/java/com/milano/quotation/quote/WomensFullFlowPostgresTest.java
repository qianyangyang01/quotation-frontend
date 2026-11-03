package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real PostgreSQL, migrations, readiness, logistics calculation and HTTP workflow; no mocked services. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WomensFullFlowPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired WebApplicationContext context; @Autowired ObjectMapper mapper; @Autowired JdbcClient jdbc;
    @Autowired UserAccountRepository users; @Autowired UserAccountService userService;
    @Autowired QuotationRecordRepository records; @Autowired QuotationReviewRepository reviews;
    MockMvc mvc; RequestPostProcessor employee,finance; String owner;
    final Map<String,UUID> channels=new HashMap<>(), versions=new HashMap<>();
    @BeforeEach void setup() throws Exception {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();owner="FLOW"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        employee=login(owner,"employee");finance=login("F"+owner,"finance");
        if(jdbc.sql("select count(*) from logistics_channel where rule_id=9910").query(Integer.class).single()==0) {
            seedChannel(0,"燕文",75,18);seedChannel(2,"万邦",91,22);
            for(var sku:List.of("NZ-DEMO-001","NZ-DEMO-002"))
                jdbc.sql("insert into purchase_product(id,sku,payload,version,catalog_state,quote_ready,created_at,updated_at) values(:id,:sku,cast(:p as jsonb),0,'ready',true,now(),now())")
                    .param("id",UUID.randomUUID()).param("sku",sku).param("p",mapper.createObjectNode().put("sku",sku).put("taxPoint",0).put("invoiceType","普票").toString()).update();
        }
        for(int n:List.of(0,2)) {
            var row=jdbc.sql("select id,current_version_id from logistics_channel where rule_id=:rule").param("rule",9910+n).query().singleRow();
            channels.put("DEMO"+n,(UUID)row.get("id"));versions.put("DEMO"+n,(UUID)row.get("current_version_id"));
        }
        setting("country-classification","[{\"country\":\"美国\",\"code\":\"US\",\"enabled\":true}]",1);
        setting("channel-policies","[{\"enabled\":true,\"category\":\"普货\",\"countryRules\":[{\"country\":\"美国\",\"allowedChannels\":[\"9910::燕文::DEMO0\",\"9912::万邦::DEMO2\"]}]}]",1);
        setting("customer-grades","[{\"grade\":\"S\",\"coefficient\":1.3,\"enabled\":true}]",1);
        setting("exchange-rate","{\"usdCny\":7,\"updatedAt\":\"test\"}",1);
        setting("tax-settings","{\"countries\":[],\"providers\":[],\"updatedAt\":\"test\"}",1);
        setting("surcharge-settings","{\"enabled\":false,\"countries\":[]}",1);
        setting("customer-operation-fees","{\"customers\":[]}",1);
        var policy=(ObjectNode)fixture("single").path("womensPricing").deepCopy();policy.remove("version");setting("womens-pricing",policy.toString(),1);
    }
    RequestPostProcessor login(String account,String role) {users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));return user(userService.loadUserByUsername(account));}
    void setting(String key,String value,long version) {
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values(:k,cast(:p as jsonb),:v,now()) on conflict(setting_key) do update set payload=excluded.payload,version=excluded.version")
            .param("k",key).param("p",value).param("v",version).update();
    }
    void seedChannel(int n,String name,int price,int fee) {
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,code,payload,version,created_at,updated_at) values(:id,:code,cast(:p as jsonb),0,now(),now())")
            .param("id",p).param("code","FLOW"+n).param("p",mapper.createObjectNode().put("id",p.toString()).put("code","FLOW"+n).put("name",name).put("enabled",true).toString()).update();
        var cp=mapper.createObjectNode().put("id",c.toString()).put("providerId",p.toString()).put("name","服装专线（示例）").put("code","DEMO"+n).put("logisticsAttribute","普货").put("enabled",true);
        jdbc.sql("insert into logistics_channel(id,provider_id,code,rule_id,payload,version,created_at,updated_at) values(:id,:p,:code,:rule,cast(:body as jsonb),0,now(),now())")
            .param("id",c).param("p",p).param("code","DEMO"+n).param("rule",9910+n).param("body",cp.toString()).update();
        var vp=mapper.createObjectNode().put("id",v.toString()).put("channelId",c.toString()).put("versionNumber",1).put("status","published");
        vp.putArray("rows").addObject().put("areaName","美国").put("countryCode","US").put("weightFromKg",0).put("weightToKg",30).put("pricePerKg",price).put("registrationFee",fee).put("allowedMarks","").put("prohibitedMarks","");
        vp.putArray("issues");vp.putArray("diffRows");
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:c,1,'published','flow',cast(:p as jsonb),now(),now())").param("id",v).param("c",c).param("p",vp.toString()).update();
        jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();
        // Published legacy tariff fixture; every submitted charge is still recalculated by the real guard.
        jdbc.sql("insert into logistics_billing_acceptance select gen_random_uuid(),id,md5((payload->'rows')::text),'legacy','legacy','{}','QA',now() from logistics_version where id=:v").param("v",v).update();
    }
    ObjectNode fixture(String mode) throws Exception {return (ObjectNode)mapper.readTree(getClass().getResourceAsStream("/womens-pricing/"+mode+".json"));}
    ObjectNode input(String mode,String reference,long version) throws Exception {
        return input(mode,reference,version,false);
    }
    ObjectNode input(String mode,String reference,long version,boolean ordinary) throws Exception {
        var q=fixture(mode);q.remove(List.of("id","no","_version","createdAt","purchaseVersions"));q.put("customerName",owner).put("logisticsSyncScope","selected");
        var policy=(ObjectNode)q.path("womensPricing");policy.put("referenceCoefficient",new BigDecimal(reference)).put("version",version);
        ((ObjectNode)q.path("financeVersions")).put("womens-pricing",version);
        var pv=q.putObject("purchaseVersions");
        jdbc.sql("select sku,version,updated_at from purchase_product").query().listOfRows().stream().filter(row->q.path("primarySku").asText().contains(row.get("sku").toString())).forEach(row->pv.put(row.get("sku").toString(),row.get("version")+":"+((java.sql.Timestamp)row.get("updated_at")).toInstant()));
        for(var node:q.path("quoteOptions")) {
            var option=(ObjectNode)node;var code=option.path("channelCode").asText();option.put("logisticsChannelId",channels.get(code).toString()).put("logisticsVersionId",versions.get(code).toString());
            var prices=new ArrayList<BigDecimal>();
            for(var sample:option.path("womensPricingSamples")) {
                var s=(ObjectNode)sample;var cost=new BigDecimal(s.path("costCny").asText());var ref=cost.multiply(new BigDecimal(reference));
                var usd=ref.divide(BigDecimal.valueOf(7),20,RoundingMode.HALF_UP);JsonNode hit=null;
                for(var tier:policy.path("tiers"))if(usd.compareTo(new BigDecimal(tier.path("fromUsd").asText()))>=0&&(tier.path("toUsd").isNull()||usd.compareTo(new BigDecimal(tier.path("toUsd").asText()))<0)){hit=tier;break;}
                assertNotNull(hit);var factor=ordinary?new BigDecimal("1.3"):new BigDecimal(hit.path("coefficient").asText());var base=cost.multiply(factor);
                s.set("fromUsd",hit.path("fromUsd"));s.set("toUsd",hit.path("toUsd"));s.put("coefficient",factor).put("referenceQuoteCny",ref).put("baseQuoteCny",base);
                prices.add(base.divide(new BigDecimal("0.35"),0,RoundingMode.CEILING).multiply(new BigDecimal("0.05")));
            }
            for(int n=0;n<3;n++)option.put("quote"+(n+1)+"Usd",prices.get(n));option.put("quoteCustomUsd",prices.get(2)).put("quoteCny",prices.get(2).multiply(BigDecimal.valueOf(7)));
            option.put("profitCny",prices.get(2).multiply(BigDecimal.valueOf(7)).subtract(new BigDecimal(option.path("totalCostCny").asText())));
            for(var key:List.of("customerQuote","systemQuantityQuotes","sheetQuote"))for(var row:q.path(key).path("rows"))if(row.path("optionId").equals(option.path("id"))) {
                var ps=((ObjectNode)row).putArray("prices");prices.forEach(ps::add);
            }
            if(option.path("isPrimary").asBoolean())q.put("systemQuoteUsd",prices.getFirst()).put("systemQuoteCny",prices.getFirst().multiply(BigDecimal.valueOf(7)));
        }
        q.remove("specifiedQuotes");
        if(ordinary) {
            q.put("quoteMode",mode).put("customerGrade","S级客户");q.remove("womensPricing");((ObjectNode)q.path("financeVersions")).remove("womens-pricing");
            q.path("quoteOptions").forEach(o->((ObjectNode)o).remove("womensPricingSamples"));
        }
        return q;
    }
    ResultActions submit(ObjectNode q) throws Exception {return mvc.perform(post("/api/v1/quotations").with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(q.toString()));}
    JsonNode create(ObjectNode q) throws Exception {return mapper.readTree(submit(q).andDo(r->{if(r.getResponse().getStatus()>=400)System.out.println(r.getResponse().getContentAsString());}).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode view(UUID id) throws Exception {return mapper.readTree(mvc.perform(get("/api/v1/quotations/{id}",id).with(finance)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    void review(UUID id,String action) throws Exception {
        var body=mapper.createObjectNode().put("_version",records.findById(id).orElseThrow().version).put("_reviewVersion",reviews.findById(id).map(r->r.version).orElse(0L)).put("action",action).put("note","核对女装系数及逐数量快照");
        if(action.equals("complete"))body.put("financeReviewStatus","approved");
        mvc.perform(patch("/api/v1/quotations/{id}/finance-review",id).with(finance).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isOk());
    }
    List<Map<String,Object>> snapshot(UUID id) {return jdbc.sql("select to_jsonb(q)::text as quotation,to_jsonb(r)::text as review from quotation_record q left join quotation_review r on r.id=q.id where q.id=:id").param("id",id).query().listOfRows();}
    @ParameterizedTest @ValueSource(strings={"single","bundle"})
    void savesAuditsChangesPolicyAndReissuesWithoutMutatingHistory(String mode) throws Exception {
        var ordinaryInput=input(mode,"1.23",1,true);var ordinary=create(ordinaryInput);var ordinaryId=UUID.fromString(ordinary.path("id").asText());
        review(ordinaryId,"claim");review(ordinaryId,"complete");var ordinaryBefore=snapshot(ordinaryId);
        var firstInput=input(mode,"1.23",1);var first=create(firstInput);var id=UUID.fromString(first.path("id").asText());
        assertEquals("womens-"+mode,first.path("quoteMode").asText());assertEquals("pending",first.path("financeReviewStatus").asText());
        for(int i=0;i<2;i++)assertEquals(mapper.readTree(firstInput.path("quoteOptions").get(i).path("womensPricingSamples").toString()),view(id).path("quoteOptions").get(i).path("womensPricingSamples"));
        review(id,"claim");review(id,"complete");var approved=view(id);assertEquals("approved",approved.path("financeReviewStatus").asText());assertFalse(approved.path("financeReviewedAt").asText().isBlank());
        var before=snapshot(id);var policy=(ObjectNode)firstInput.path("womensPricing").deepCopy();policy.remove("version");policy.put("referenceCoefficient",1.5);
        mvc.perform(put("/api/v1/finance-settings/womens-pricing").with(finance).with(csrf()).header("If-Match",1).contentType("application/json").content(policy.toString())).andExpect(status().isOk());
        submit(firstInput).andExpect(status().isConflict());
        var changed=input(mode,"1.5",2);var second=create(changed);var newId=UUID.fromString(second.path("id").asText());assertNotEquals(id,newId);assertNotEquals(first.path("no"),second.path("no"));
        assertEquals("pending",second.path("financeReviewStatus").asText());assertFalse(second.path("quoteConfirmed").asBoolean());assertEquals(2,second.path("womensPricing").path("version").asInt());
        assertNotEquals(first.path("quoteOptions").get(0).path("womensPricingSamples"),second.path("quoteOptions").get(0).path("womensPricingSamples"));
        for(var status:List.of("approved","pending"))mvc.perform(get("/api/v1/quotations/search").with(finance).param("scope","company").param("q",owner).param("quoteMode","womens-"+mode).param("reviewStatus",status)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        assertEquals(before,snapshot(id));assertEquals(approved,view(id));
        var corrupt=changed.deepCopy();((ObjectNode)corrupt.path("quoteOptions").get(0).path("womensPricingSamples").get(0)).put("coefficient",9);
        submit(corrupt).andExpect(status().isUnprocessableEntity());assertEquals(before,snapshot(id));
        review(newId,"claim");review(newId,"complete");assertEquals("approved",view(newId).path("financeReviewStatus").asText());assertEquals(before,snapshot(id));
        policy.put("enabled",false);
        mvc.perform(put("/api/v1/finance-settings/womens-pricing").with(finance).with(csrf()).header("If-Match",2).contentType("application/json").content(policy.toString())).andExpect(status().isOk());
        var disabled=input(mode,"1.5",3);((ObjectNode)disabled.path("womensPricing")).put("enabled",false);submit(disabled).andExpect(status().isConflict());
        var ordinaryAgain=create(input(mode,"1.5",3,true));assertEquals(ordinary.path("systemQuantityQuotes"),ordinaryAgain.path("systemQuantityQuotes"));
        assertEquals(ordinaryBefore,snapshot(ordinaryId));assertEquals(before,snapshot(id));
    }
}

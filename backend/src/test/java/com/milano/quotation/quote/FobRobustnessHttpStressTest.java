package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP load against a disposable PostgreSQL database; never targets a supplied URL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="quotation.fob.stress",matches="true")
class FobRobustnessHttpStressTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container static final org.testcontainers.containers.GenericContainer<?> redis=new org.testcontainers.containers.GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
        r.add("spring.data.redis.host",redis::getHost);r.add("spring.data.redis.port",()->redis.getMappedPort(6379));
    }
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired Environment environment;
    @Autowired QuotationRecordRepository records;
    final Map<String,List<Double>> samples=new ConcurrentHashMap<>();
    final List<String> checks=new ArrayList<>();
    final AtomicInteger requests=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
    record Reply(int status,JsonNode body) { JsonNode data(){return body.path("data");} }
    class Client implements AutoCloseable {
        final String account,role;
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        JsonNode csrf;
        Client(String account,String role) {this.account=account;this.role=role;}
        Reply call(String name,String method,String path,JsonNode body,int expected) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api/v1"+path)).timeout(Duration.ofSeconds(30));
            if(csrf!=null&&!method.equals("GET")) builder.header(csrf.path("headerName").asText(),csrf.path("token").asText());
            if(body!=null) builder.header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
            long start=System.nanoTime();peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try {
                var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());requests.incrementAndGet();
                samples.computeIfAbsent(name,k->Collections.synchronizedList(new ArrayList<>())).add((System.nanoTime()-start)/1e6);
                assertEquals(expected,response.statusCode(),method+" "+path+" "+response.body());
                return new Reply(response.statusCode(),mapper.readTree(response.body()));
            } finally {active.decrementAndGet();}
        }
        JsonNode get(String path) throws Exception {return call("read","GET",path,null,200).data();}
        void login() throws Exception {
            csrf=get("/auth/csrf");call("login","POST","/auth/login",mapper.createObjectNode().put("account",account).put("password","StressOnly123!"),200);
        }
        public void close(){http.close();}
    }
    ObjectNode row(String sku,String price) {return mapper.createObjectNode().put("sku",sku).put("moqRaw","1").put("priceRaw",price).put("freightRaw","100件18.5;200件40");}
    ObjectNode preview(Client c,ArrayNode rows) throws Exception {
        var p=c.call("preview","POST","/fob-purchase-products/paste/preview",rows,200).data();assertTrue(p.path("canSave").asBoolean(),p.toString());
        var confirmation=mapper.createObjectNode().put("digest",p.path("digest").asText());confirmation.set("rows",rows);
        var expected=confirmation.putArray("expected");for(var r:p.path("rows")) expected.add(r.path("expected"));return confirmation;
    }
    JsonNode confirm(Client c,ObjectNode body,int status) throws Exception {return c.call("confirm","POST","/fob-purchase-products/paste/confirm",body,status).data();}
    String fingerprint(String table) {return jdbc.sql("select coalesce(md5(string_agg(to_jsonb(t)::text, '' order by id)), '') from "+table+" t").query(String.class).single();}
    @Test @Timeout(600) void exceptionsRollbackConcurrentWritersAndFiftyAuthenticatedReaders() throws Exception {
        var clients=new ArrayList<Client>();long began=System.nanoTime();boolean passed=false;
        try {
            String hash=encoder.encode("StressOnly123!");
            for(int i=0;i<50;i++) {
                String role=i<10?"super_admin":i<20?"finance":"employee",account="FOBSTRESS"+i;
                users.saveAndFlush(UserAccount.create(account,account,hash,role,false));var c=new Client(account,role);clients.add(c);c.login();
            }
            var admin=clients.getFirst();String source="/fob-purchase-products/LOAD-SHARED";
            confirm(admin,preview(admin,mapper.createArrayNode().add(row("LOAD-SHARED","单价8.54;200-209件7.13;210件以上6.64"))),200);
            var expected=admin.get(source);var finance=admin.get("/finance-settings");
            // Seed immutable synthetic snapshots for every employee; no production records are loaded.
            var ids=new ArrayList<UUID>();
            for(var c:clients) {
                var r=new QuotationRecordEntity();r.id=UUID.randomUUID();r.quoteNo="LOAD-"+c.account;r.ownerAccount=c.account;r.status="pending";
                r.createdAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);r.updatedAt=r.createdAt;
                r.payload=mapper.createObjectNode().put("id",r.id.toString()).put("no",r.quoteNo).put("systemQuoteUsd",6.35).put("exchangeRate",6.7);
                records.saveAndFlush(r);ids.add(r.id);
            }
            String quoteBefore=fingerprint("quotation_record"),purchaseBefore=fingerprint("purchase_product");
            // Input corruption and optimistic conflict must never partially persist a batch.
            for(JsonNode invalid:List.of(mapper.createArrayNode(),mapper.createArrayNode().addNull(),mapper.createArrayNode().add(row("INVALID-FIELD","8").put("dataSource","standard")),mapper.createArrayNode().add(row("INVALID-LENGTH","8").put("notes","x".repeat(10001)))))
                admin.call("invalid-input","POST","/fob-purchase-products/paste/preview",invalid,422);
            var tooMany=mapper.createArrayNode();for(int i=0;i<101;i++)tooMany.add(row("BOUND-"+i,"8"));
            admin.call("batch-limit","POST","/fob-purchase-products/paste/preview",tooMany,422);
            for(String price:List.of("150-299套17.5;150-299套17","单价27;批发价24","999999999999999999999件8","<script>alert(1)</script>","1-10件8;20件7")) {
                var r=admin.call("invalid-source","POST","/fob-purchase-products/paste/preview",mapper.createArrayNode().add(row("INVALID-PARSE",price)),200).data();assertFalse(r.path("canSave").asBoolean());
            }
            var stale=preview(admin,mapper.createArrayNode().add(row("LOAD-SHARED","9")).add(row("MUST-NOT-EXIST","9")));
            confirm(admin,preview(admin,mapper.createArrayNode().add(row("LOAD-SHARED","10"))),200);confirm(admin,stale,409);
            admin.call("atomic-readback","GET","/fob-purchase-products/MUST-NOT-EXIST",null,404);
            confirm(admin,preview(admin,mapper.createArrayNode().add(row("LOAD-SHARED","单价8.54;200-209件7.13;210件以上6.64"))),200);expected=admin.get(source);
            checks.add("Malformed input, ambiguous sources, 101-row limit and stale batch rejected atomically");
            // Simulate a database failure after an earlier row has already been inserted.
            jdbc.sql("create function fob_injected_failure() returns trigger language plpgsql as $$ begin if NEW.sku='ROLLBACK-Z' then raise exception 'isolated injected failure'; end if; return NEW; end $$").update();
            jdbc.sql("create trigger fob_injected_failure before insert on fob_purchase_product for each row execute function fob_injected_failure()").update();
            var rollback=preview(admin,mapper.createArrayNode().add(row("ROLLBACK-A","8")).add(row("ROLLBACK-Z","8")));
            try {confirm(admin,rollback,500);} finally {jdbc.sql("drop trigger fob_injected_failure on fob_purchase_product").update();jdbc.sql("drop function fob_injected_failure()").update();}
            for(String sku:List.of("ROLLBACK-A","ROLLBACK-Z")) {admin.call("rollback-readback","GET","/fob-purchase-products/"+sku,null,404);assertTrue(admin.get("/fob-purchase-products/"+sku+"/history").isEmpty());}
            assertEquals(2,confirm(admin,rollback,200).path("added").asInt());checks.add("Injected database failure rolls back both data and audit; retry succeeds");
            // A full-sized paste must be visible consistently to every authorized account.
            var hundred=mapper.createArrayNode();for(int i=0;i<100;i++)hundred.add(row("HUNDRED-"+i,"8"));
            assertEquals(100,confirm(admin,preview(admin,hundred),200).path("added").asInt());assertEquals(100,confirm(admin,preview(admin,hundred),200).path("unchanged").asInt());
            var ref=expected;int seconds=Integer.getInteger("quotation.fob.stress.seconds",120);
            try(var pool=Executors.newFixedThreadPool(50)) {
                var ready=new CountDownLatch(50);var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
                long[] end={0};
                for(int index=0;index<50;index++) {int n=index;var c=clients.get(index);tasks.add(pool.submit(()->{
                    ready.countDown();start.await();int round=0;
                    do {
                        assertEquals(ref,c.get(source),"Shared FOB data differs by account");
                        assertEquals(finance,c.get("/finance-settings"),"Finance settings differ by account");
                        String quote="/quotations/"+ids.get(n);
                        if(n<10) {
                            // All administrators race to mark the same employee's quotation; only one audit is allowed.
                            c.call("spot-check","PATCH","/quotations/"+ids.get(20)+"/spot-check",mapper.createObjectNode().put("_version",0),200);
                            if(round%10==0) {String sku="LOAD-"+n+"-"+round;assertEquals(1,confirm(c,preview(c,mapper.createArrayNode().add(row(sku,"9"))),200).path("added").asInt());}
                        } else if(round==0) c.call("write-denied","PATCH",quote+"/spot-check",mapper.createObjectNode().put("_version",0),403);
                        var snapshot=c.get(quote);assertEquals(6.35,snapshot.path("systemQuoteUsd").asDouble());
                        var states=c.get("/quotations/review-status?ids="+ids.get(n));assertEquals(1,states.size());
                        var list=c.get("/quotations/search?scope=mine&page=0&size=10");assertTrue(list.path("items").size()>0);
                        if(round++%10==0) assertEquals(8,c.get("/fob-purchase-products/HUNDRED-"+n).path("parsed").path("priceTiers").get(0).path("unitPriceCny").asInt());
                    }while(System.nanoTime()<end[0]);return null;
                }));}
                assertTrue(ready.await(10,TimeUnit.SECONDS));end[0]=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);start.countDown();
                for(var task:tasks)task.get(seconds+120L,TimeUnit.SECONDS);
            }
            checks.add("50 independent authenticated sessions share identical FOB/finance data under mixed reads, writes and inspection marking");
            assertEquals(1L,jdbc.sql("select count(*) from audit_log where action='quotation.spot-check' and resource_id=:id").param("id",ids.get(20).toString()).query(Long.class).single());
            var marked=admin.get("/quotations/"+ids.get(20));assertTrue(marked.path("spotChecked").asBoolean());
            assertEquals(marked,clients.get(20).get("/quotations/"+ids.get(20)));assertEquals(quoteBefore,fingerprint("quotation_record"));assertEquals(purchaseBefore,fingerprint("purchase_product"));
            checks.add("Repeated concurrent inspection emits one audit; owner/admin agree; historical snapshots and ordinary procurement unchanged");passed=true;
        } finally {
            var report=mapper.createObjectNode().put("passed",passed).put("accounts",clients.size()).put("requests",requests.get()).put("peakRequests",peak.get()).put("elapsedSeconds",(System.nanoTime()-began)/1e9);
            report.set("checks",mapper.valueToTree(checks));var metrics=report.putObject("metrics");
            samples.forEach((name,values)->{var sorted=values.stream().sorted().toList();var m=metrics.putObject(name).put("count",sorted.size());for(double p:new double[]{.5,.95,.99,1})m.put(p==1?"maxMs":"p"+(int)(p*100)+"Ms",sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*p)-1)));});
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/fob-robustness-stress.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            for(var c:clients)c.close();
        }
    }
}

package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import com.milano.quotation.common.Jackson3HibernateConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.minio.MinioClient;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** One-shot official service workflow; never starts migrations, schedulers or quotation recalculation. */
public class RunBaizhou {
    static final String COMPANY="fe84a955-c584-4ed5-9b0e-1c79957c1c83",ACTOR="codex-maintenance";
    static final Set<String> COUNTRIES=Set.of("美国");
    static final Set<String> TAX_SCOPES=Set.of("美国");
    static final Path ROOT=Path.of("/maintenance");
    static final ObjectMapper M=new ObjectMapper();
    static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static void save(String name,JsonNode value)throws Exception{Files.writeString(ROOT.resolve(name+".json"),value.toPrettyString());}
    static void audit(JdbcClient jdbc,String action,String id,Object detail){
        jdbc.sql("insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values(:id,'baizhou-bz503-20261116',:actor,:action,'logistics',:resource,'success',cast(:detail as jsonb),now())")
            .param("id",UUID.randomUUID()).param("actor",ACTOR).param("action",action).param("resource",id).param("detail",M.writeValueAsString(detail)).update();
    }
    static void reconcile(JsonNode actual,JsonNode expected){
        require(actual.path("errors").asInt()==0&&actual.path("pricingReady").asBoolean()&&actual.path("rows").size()==7,"Invalid channel "+actual.path("issues"));
        require(actual.path("companyChannelId").asText().equals(COMPANY),"Wrong company identity");
        for(var row:expected.path("rows")){
            var hits=actual.path("rows").valueStream().filter(r->r.path("sourceRow").equals(row.path("sourceRow"))).toList();require(hits.size()==1,"Wrong source row");
            for(var f:row.properties()){var a=hits.getFirst().path(f.getKey());var e=f.getValue();require(e.isNumber()?a.isNumber()&&e.decimalValue().compareTo(a.decimalValue())==0:e.equals(a),"Mismatch "+f.getKey()+row.path("sourceRow"));}
            require(hits.getFirst().path("sourceProductCode").asText().equals("BZ503"),"Wrong product code");
        }
    }
    static void billing(JsonNode channel,JsonNode cases){
        var engine=new LogisticsBillingEngine(M);
        for(var c:cases.path("samples"))require(engine.calculate(channel.path("rows"),c).path("total").decimalValue().compareTo(c.path("total").decimalValue())==0,"Billing mismatch "+c);
        for(var c:cases.path("rejected")){boolean blocked=false;try{engine.calculate(channel.path("rows"),c);}catch(com.milano.quotation.common.AppException e){blocked=true;}require(blocked,"Expected rejection "+c);}
    }
    public static void main(String[] args)throws Exception{
        require(Set.of("check","import","publish","verify").contains(args[0]),"Invalid action");
        require(LogisticsSourceParser.VERSION.equals("baizhou-bz503-2026.11.16-v20"),"Wrong parser");
        var expected=M.readTree(Files.readString(ROOT.resolve("expected.json")));var config=(ObjectNode)M.readTree(Files.readString(ROOT.resolve("company.json")));var cases=M.readTree(Files.readString(ROOT.resolve("billing-cases.json")));
        byte[] bytes=Files.readAllBytes(ROOT.resolve("source.xlsx"));require(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected.path("sha256").asText()),"Source changed");
        try(var ds=new HikariDataSource()){
            ds.setJdbcUrl(env("SPRING_DATASOURCE_URL"));ds.setUsername(env("SPRING_DATASOURCE_USERNAME"));ds.setPassword(env("SPRING_DATASOURCE_PASSWORD"));ds.setMaximumPoolSize(2);
            var jdbc=JdbcClient.create(ds);var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);factory.setPackagesToScan("com.milano.quotation.logistics");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Map<String,Object> props=new HashMap<>();props.put("hibernate.hbm2ddl.auto","validate");props.put("hibernate.jdbc.time_zone","UTC");
            var jsonMethod=Jackson3HibernateConfig.class.getDeclaredMethod("jackson3JsonFormatMapper",ObjectMapper.class);jsonMethod.setAccessible(true);((HibernatePropertiesCustomizer)jsonMethod.invoke(new Jackson3HibernateConfig(),M)).customize(props);
            factory.setJpaPropertyMap(props);factory.afterPropertiesSet();
            try{
                var emf=factory.getObject();var manager=new JpaTransactionManager(emf);manager.setDataSource(ds);manager.afterPropertiesSet();var tx=new TransactionTemplate(manager);
                var em=SharedEntityManagerCreator.createSharedEntityManager(emf);var repos=new JpaRepositoryFactory(em);var books=new LogisticsWorkbookService(M);var guard=new LogisticsDatasetGuard(jdbc);
                require(guard.activeId().toString().equals("bd07399a-1e05-4886-b3f6-699ee9c64919"),"Active dataset changed");
                var logistics=new LogisticsService(repos.getRepository(LogisticsProviderRepository.class),repos.getRepository(LogisticsChannelRepository.class),repos.getRepository(LogisticsVersionRepository.class),books,guard);
                var parser=new LogisticsSourceParser(M,books);
                var directory=new CompanyChannelService(jdbc,M){@Override public ObjectNode scopeFor(UUID provider,UUID channel){var all=super.scopeFor(provider,channel);var original=all.path("entries").deepCopy();var selected=all.putArray("entries");for(var e:original)if(e.path("id").asText().equals(COMPANY))selected.add(e);require(selected.size()==1,"New channel scope missing");return all;}};
                var client=new LogisticsParserClient(parser,M,env("APP_LOGISTICS_PARSER_URL"));var parsed=client.parse(bytes,expected.path("source").asText(),new CompanyChannelScope(config));
                require(parsed.path("channels").size()==1&&parsed.path("parserVersion").asText().equals(LogisticsSourceParser.VERSION),"Wrong parser/scope");
                reconcile(parsed.path("channels").get(0),expected);billing(parsed.path("channels").get(0),cases);save("live-parsed",parsed);
                if(args[0].equals("check")){System.out.println("BAIZHOU_CHECK_OK 7 US rows");return;}
                if(args[0].equals("import")){
                    save("directory-before",directory.list());
                    tx.executeWithoutResult(status->{var current=directory.list();require(current.path("revision").asInt()==24,"Directory changed");require(current.path("entries").valueStream().noneMatch(e->e.path("id").asText().equals(COMPANY)||e.path("productCodes").toString().contains("BZ503")),"Already registered");current.withArray("entries").add(config.path("entries").get(0));var result=directory.save(current,ACTOR);require(result.path("revision").asInt()==25&&result.path("entries").size()==131,"Directory mismatch");audit(jdbc,"logistics.company-directory",COMPANY,config.path("entries").get(0));});
                    save("directory-after",directory.list());
                    var storage=new AssetStorageService(MinioClient.builder().endpoint(env("APP_STORAGE_ENDPOINT")).credentials(env("APP_STORAGE_ACCESS_KEY"),env("APP_STORAGE_SECRET_KEY")).build(),null,env("APP_STORAGE_BUCKET"),false);
                    var imports=new LogisticsImportService(jdbc,M,storage,parser,logistics,guard,manager,Runnable::run);
                    for(var e:Map.of("companyChannels",(Object)directory,"isolatedParser",client).entrySet()){var f=LogisticsImportService.class.getDeclaredField(e.getKey());f.setAccessible(true);f.set(imports,e.getValue());}
                    MultipartFile file=new MultipartFile(){public String getName(){return "files";}public String getOriginalFilename(){return expected.path("source").asText();}public String getContentType(){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}public boolean isEmpty(){return false;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes);}public void transferTo(java.io.File dest)throws java.io.IOException{Files.write(dest.toPath(),bytes);}};
                    var accepted=imports.uploadSelected(guard.activeId(),List.of(file),ACTOR,"baizhou-bz503-20261116",false,null,null);
                    var batch=imports.get(UUID.fromString(accepted.path("id").asText()));save("import-result",batch);var results=batch.path("payload").path("results");
                    require(batch.path("status").asText().equals("completed")&&results.size()==1&&results.get(0).path("status").asText().equals("draft")&&results.get(0).path("errors").asInt()==0,"Import failed");
                    tx.executeWithoutResult(status->audit(jdbc,"logistics.import",batch.path("id").asText(),results));System.out.println("BAIZHOU_IMPORT_OK "+results);return;
                }
                var batch=M.readTree(Files.readString(ROOT.resolve("import-result.json")));var item=batch.path("payload").path("results").get(0);var channelId=UUID.fromString(item.path("channelId").asText());var versionId=UUID.fromString(item.path("versionId").asText());
                var acceptance=new LogisticsBillingAcceptanceService(jdbc,M,guard,new LogisticsBillingEngine(M));
                if(args[0].equals("publish")){
                    var service=new LogisticsBatchPublishService(jdbc,M,logistics,acceptance,manager);var coverage=service.coverage(UUID.fromString(batch.path("id").asText()));save("publication-coverage",coverage);require(coverage.path("missingCount").asInt()==0,"Unexpected missing company channels");
                    save("finance-before",jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->M.readTree(rs.getString(1))).single());
                    tx.executeWithoutResult(status->{
                        var v=acceptance.version(versionId);require(v.path("status").asText().equals("draft")&&v.path("currentVersionId").isNull(),"Version changed");reconcile(v.path("payload"),expected);
                        String note="2026-11-16 用户确认新增百州BZ503仅美国：包税、全邮编、仅服装，覆盖原表税费及邮编限制。1g起计、重量向上进位1g，公斤价乘计费重量加一次挂号费后合计向上取整到人民币元。原表美国7档14个价格字段逐项核对，前后端各30000整克及各档边界样本验证；保留尺寸及特殊包裹复核。历史报价和审核不改写。";
                        logistics.publishReviewed(channelId,versionId,note,ACTOR,false,true,true);em.flush();
                        var approval=acceptance.status(versionId).put("reviewConfirmed",true).put("note",note).put("sourceReference",expected.path("source").asText()+" / BZ-欧美服装专线挂号 A3:F10");var samples=approval.putArray("samples");
                        for(var c:cases.path("samples")){var s=samples.addObject().put("sourceReference","原表C"+c.path("sourceRow").asInt()+":F"+c.path("sourceRow").asInt()+"；用户确认1g和人民币元进位").set("expectedTotal",c.path("total"));s.putObject("input").put("country",c.path("country").asText()).set("weightKg",c.path("weightKg"));}
                        for(var c:cases.path("rejected"))samples.addObject().put("sourceReference","零重、超重及无价格国家拒绝").put("expectRejected",true).set("input",c);
                        acceptance.approve(versionId,approval,ACTOR);require(guard.quoteReady(versionId),"Not quote ready");
                        var policy=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies' for update").query((rs,n)->M.readTree(rs.getString(1))).single();
                        var tax=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='tax-settings' for update").query((rs,n)->M.readTree(rs.getString(1))).single();require(policy.path("version").asInt()==66&&tax.path("version").asInt()==44,"Finance changed");
                        String key=jdbc.sql("select rule_id::text||'::百州::'||code from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
                        var next=policy.path("payload").deepCopy();int added=0;
                        for(var p:next)for(var c:p.path("countryRules")){
                            require(c.path("allowedChannels").valueStream().noneMatch(k->k.asText().equals(key)),"Already authorized");
                            if(p.path("category").asText().equals("服装")&&COUNTRIES.contains(c.path("country").asText())){require(p.path("enabled").asBoolean(),"Clothing disabled");((ObjectNode)c).withArray("allowedChannels").add(key);added++;}
                        }
                        require(added==1,"Wrong clothing country count");com.milano.quotation.finance.ValidateKuailvFinance.validate(next);
                        var nextTax=tax.path("payload").deepCopy();int taxAdded=0;
                        for(var c:nextTax.path("countries"))if(TAX_SCOPES.contains(c.path("country").asText())){require(c.path("selected").asBoolean(),"Tax scope inactive");require(c.path("channelRules").valueStream().noneMatch(k->k.path("key").asText().equals(key)),"Already taxed");((ObjectNode)c).withArray("channelRules").addObject().put("key",key).put("mode","exempt").put("amount",0).put("currency","USD").put("perKg",0).put("source","2026-11-16用户确认：百州BZ503服装渠道包税、全邮编，覆盖原表税费及邮编限制");taxAdded++;}
                        require(taxAdded==1,"Wrong tax scope count");com.milano.quotation.finance.ValidateKuailvFinance.validateTax(nextTax);
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='channel-policies' and version=66 and payload=cast(:old as jsonb)").param("p",next.toString()).param("old",policy.path("payload").toString()).update()==1,"Policy race");
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='tax-settings' and version=44 and payload=cast(:old as jsonb)").param("p",nextTax.toString()).param("old",tax.path("payload").toString()).update()==1,"Tax race");
                        audit(jdbc,"finance.channel-and-tax",key,Map.of("category","服装","countries",COUNTRIES,"taxMode","exempt","fullPostalCoverage",true));audit(jdbc,"logistics.publish",versionId.toString(),Map.of("rows",7,"sourceSha256",expected.path("sha256").asText(),"channelId",channelId.toString()));
                    });
                    System.out.println("BAIZHOU_PUBLISHED_OK "+versionId);
                }
                require(guard.quoteReady(versionId),"New version unavailable");var published=acceptance.version(versionId);reconcile(published.path("payload"),expected);billing(published.path("payload"),cases);
                var rules=new LogisticsQueryService(jdbc,M).publishedRules(null,"服装",List.of("US"),List.of()).rules();
                var hits=rules.stream().filter(r->r.path("relations").valueStream().anyMatch(x->x.path("carrier").asText().equals("百州")&&x.path("channel").asText().equals("BZ-欧美服装专线挂号"))).toList();
                require(hits.size()==1&&hits.getFirst().path("billingVerified").asBoolean()&&hits.getFirst().path("prices").size()==7,"Quote projection mismatch");var projected=M.createArrayNode();hits.forEach(projected::add);save("published-rules",projected);save("publication-status",acceptance.status(versionId));
                String key=jdbc.sql("select rule_id::text||'::百州::'||code from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
                var finance=jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->M.readTree(rs.getString(1))).single();save("finance-after",finance);int policyCount=0,taxCount=0;
                for(var p:finance.path("channel-policies").path("payload"))for(var c:p.path("countryRules"))if(c.path("allowedChannels").valueStream().anyMatch(k->k.asText().equals(key))){require(p.path("category").asText().equals("服装")&&COUNTRIES.contains(c.path("country").asText()),"Non-clothing authorization");policyCount++;}
                for(var c:finance.path("tax-settings").path("payload").path("countries"))for(var t:c.path("channelRules"))if(t.path("key").asText().equals(key)){require(TAX_SCOPES.contains(c.path("country").asText())&&c.path("selected").asBoolean()&&t.path("mode").asText().equals("exempt")&&t.path("amount").asDouble()==0&&t.path("perKg").asDouble()==0,"Tax mismatch");taxCount++;}
                require(policyCount==1&&taxCount==1,"Finance readback mismatch");save("final-verification",M.createObjectNode().put("passed",true).put("channelId",channelId.toString()).put("versionId",versionId.toString()).put("rows",7).put("countries",1).put("category","服装").put("taxMode","exempt").put("allPostalCodes",true).put("channelKey",key));System.out.println("BAIZHOU_VERIFIED_OK "+versionId);
            }finally{factory.destroy();}
        }
    }
}

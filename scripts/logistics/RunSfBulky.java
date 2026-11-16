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
public class RunSfBulky {
    static final String COMPANY="10bd9c26-404d-4f4f-bb12-dd86b832f968",ACTOR="codex-maintenance";
    static final Set<String> COUNTRIES=Set.of("丹麦","保加利亚","克罗地亚","匈牙利","卢森堡","奥地利","希腊","德国","意大利","拉脱维亚","捷克","斯洛伐克","斯洛文尼亚","比利时","法国","波兰","爱尔兰","爱沙尼亚","瑞典","立陶宛","罗马尼亚","芬兰","英国","荷兰","葡萄牙","西班牙");
    static final Set<String> TAX_SCOPES=Set.of("欧盟","英国");
    static final Path ROOT=Path.of("/maintenance");
    static final ObjectMapper M=new ObjectMapper();
    static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static void save(String name,JsonNode value)throws Exception{Files.writeString(ROOT.resolve(name+".json"),value.toPrettyString());}
    static void audit(JdbcClient jdbc,String action,String id,Object detail){
        jdbc.sql("insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values(:id,'sf-europe-bulky-20261116',:actor,:action,'logistics',:resource,'success',cast(:detail as jsonb),now())")
            .param("id",UUID.randomUUID()).param("actor",ACTOR).param("action",action).param("resource",id).param("detail",M.writeValueAsString(detail)).update();
    }
    static void reconcile(JsonNode actual,JsonNode expected){ReviewSfBulky.reconcile(actual,expected);}
    static void billing(JsonNode channel,JsonNode cases){ReviewSfBulky.billing(channel,cases);}
    public static void main(String[] args)throws Exception{
        require(Set.of("check","import","publish","verify").contains(args[0]),"Invalid action");
        require(LogisticsSourceParser.VERSION.equals("sf-europe-bulky-2026.11.16-v22"),"Wrong parser");
        var expected=M.readTree(Files.readString(ROOT.resolve("expected.json")));var config=(ObjectNode)M.readTree(Files.readString(ROOT.resolve("company.json")));var cases=M.readTree(Files.readString(ROOT.resolve("billing-cases.json")));
        var baseline=M.readTree(Files.readString(ROOT.resolve("finance-baseline.json")));
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
                if(args[0].equals("check")){System.out.println("SF_BULKY_CHECK_OK 52 rows / 26 countries");return;}
                if(args[0].equals("import")){
                    save("directory-before",directory.list());
                    tx.executeWithoutResult(status->{var current=directory.list();require(current.path("revision").asInt()==config.path("revision").asInt(),"Directory changed");require(current.path("entries").valueStream().noneMatch(e->e.path("id").asText().equals(COMPANY)||e.path("providerName").asText().equals("顺丰")&&e.path("channelName").asText().equals("欧洲-泡货专线")),"Already registered");current.withArray("entries").add(config.path("entries").get(0));var result=directory.save(current,ACTOR);require(result.path("revision").asInt()==config.path("revision").asInt()+1&&result.path("entries").size()==current.path("entries").size(),"Directory mismatch");audit(jdbc,"logistics.company-directory",COMPANY,config.path("entries").get(0));});
                    save("directory-after",directory.list());
                    var storage=new AssetStorageService(MinioClient.builder().endpoint(env("APP_STORAGE_ENDPOINT")).credentials(env("APP_STORAGE_ACCESS_KEY"),env("APP_STORAGE_SECRET_KEY")).build(),null,env("APP_STORAGE_BUCKET"),false);
                    var imports=new LogisticsImportService(jdbc,M,storage,parser,logistics,guard,manager,Runnable::run);
                    for(var e:Map.of("companyChannels",(Object)directory,"isolatedParser",client).entrySet()){var f=LogisticsImportService.class.getDeclaredField(e.getKey());f.setAccessible(true);f.set(imports,e.getValue());}
                    MultipartFile file=new MultipartFile(){public String getName(){return "files";}public String getOriginalFilename(){return expected.path("source").asText();}public String getContentType(){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}public boolean isEmpty(){return false;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes);}public void transferTo(java.io.File dest)throws java.io.IOException{Files.write(dest.toPath(),bytes);}};
                    var accepted=imports.uploadSelected(guard.activeId(),List.of(file),ACTOR,"sf-europe-bulky-20261116",false,null,null);
                    var batch=imports.get(UUID.fromString(accepted.path("id").asText()));save("import-result",batch);var results=batch.path("payload").path("results");
                    require(batch.path("status").asText().equals("completed")&&results.size()==1&&results.get(0).path("status").asText().equals("draft")&&results.get(0).path("errors").asInt()==0,"Import failed");
                    tx.executeWithoutResult(status->audit(jdbc,"logistics.import",batch.path("id").asText(),results));System.out.println("SF_BULKY_IMPORT_OK "+results);return;
                }
                var batch=M.readTree(Files.readString(ROOT.resolve("import-result.json")));var item=batch.path("payload").path("results").get(0);var channelId=UUID.fromString(item.path("channelId").asText());var versionId=UUID.fromString(item.path("versionId").asText());
                var acceptance=new LogisticsBillingAcceptanceService(jdbc,M,guard,new LogisticsBillingEngine(M));
                if(args[0].equals("publish")){
                    var service=new LogisticsBatchPublishService(jdbc,M,logistics,acceptance,manager);var coverage=service.coverage(UUID.fromString(batch.path("id").asText()));save("publication-coverage",coverage);var excluded=M.readTree(Files.readString(ROOT.resolve("expected-excluded-channels.json")));
                    require(coverage.path("missingCount").asInt()==5&&coverage.path("missingChannels").equals(excluded.path("missingChannels")),"Unchanged existing SF channels differ from reviewed scope");
                    LogisticsImportCoverage.requireAcknowledgment(coverage,M.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken",coverage.path("token").asText()));
                    save("finance-before",jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->M.readTree(rs.getString(1))).single());
                    tx.executeWithoutResult(status->{
                        var v=acceptance.version(versionId);require(v.path("status").asText().equals("draft")&&v.path("currentVersionId").isNull(),"Version changed");reconcile(v.path("payload"),expected);
                        String note="2026-11-16 用户确认新增顺丰欧洲-泡货专线，普货、服装。欧盟固定5.83美元/单，英国无税；每公斤税费为0，不叠加其他顺丰渠道税费或处理费。26国52档104个价格字段与原表一致，克重向上进位后匹配档位，公斤运费加一次挂号费，人民币分四舍五入。保留一票一件、材积/18000与实重取大及尺寸、偏远邮编人工复核。历史报价和审核不修改。";
                        logistics.publishReviewed(channelId,versionId,note,ACTOR,false,true,true);em.flush();
                        var approval=acceptance.status(versionId).put("reviewConfirmed",true).put("note",note).put("sourceReference",expected.path("source").asText()+" / 欧洲-泡货专线 B3:I55；C62:F63");var samples=approval.putArray("samples");
                        for(var c:cases.path("samples")){var s=samples.addObject().put("sourceReference","原表G"+c.path("sourceRow").asInt()+":I"+c.path("sourceRow").asInt()+"；C62/C63精确至克").set("expectedTotal",c.path("total"));s.putObject("input").put("country",c.path("country").asText()).set("weightKg",c.path("weightKg"));}
                        for(var c:cases.path("rejected"))samples.addObject().put("sourceReference","零重、超重及无价格国家拒绝").put("expectRejected",true).set("input",c);
                        acceptance.approve(versionId,approval,ACTOR);require(guard.quoteReady(versionId),"Not quote ready");
                        var policy=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies' for update").query((rs,n)->M.readTree(rs.getString(1))).single();
                        var tax=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='tax-settings' for update").query((rs,n)->M.readTree(rs.getString(1))).single();
                        require(policy.path("version").equals(baseline.path("channel-policies").path("version"))&&tax.path("version").equals(baseline.path("tax-settings").path("version")),"Finance changed");
                        String key=jdbc.sql("select rule_id::text||'::顺丰::'||code from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
                        var next=policy.path("payload").deepCopy();int added=0;
                        for(var p:next)for(var c:p.path("countryRules")){
                            require(c.path("allowedChannels").valueStream().noneMatch(k->k.asText().equals(key)),"Already authorized");
                            if(Set.of("普货","服装").contains(p.path("category").asText())&&COUNTRIES.contains(c.path("country").asText())){
                                require(p.path("enabled").asBoolean(),"Category disabled");((ObjectNode)c).withArray("allowedChannels").add(key);added++;
                            }
                        }
                        require(added==52,"Wrong authorization count");com.milano.quotation.finance.ValidateKuailvFinance.validate(next);
                        var nextTax=tax.path("payload").deepCopy();int taxAdded=0;
                        for(var c:nextTax.path("countries"))if(TAX_SCOPES.contains(c.path("country").asText())){
                            require(c.path("selected").asBoolean(),"Tax scope inactive");
                            require(c.path("channelRules").valueStream().noneMatch(k->k.path("key").asText().equals(key)),"Already taxed");
                            boolean eu=c.path("country").asText().equals("欧盟");
                            ((ObjectNode)c).withArray("channelRules").addObject().put("key",key).put("mode",eu?"fixed-order":"no-tax").put("amount",eu?5.83:0).put("currency","USD").put("perKg",0)
                                .put("source","2026-11-16 用户确认：顺丰欧洲-泡货专线欧盟5.83美元/单、英国无税；不按重量加税，原表挂号费另计");taxAdded++;
                        }
                        require(taxAdded==2,"Wrong tax count");com.milano.quotation.finance.ValidateKuailvFinance.validateTax(nextTax);
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='channel-policies' and version=:version and payload=cast(:old as jsonb)")
                            .param("p",next.toString()).param("version",policy.path("version").asLong()).param("old",policy.path("payload").toString()).update()==1,"Policy race");
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='tax-settings' and version=:version and payload=cast(:old as jsonb)")
                            .param("p",nextTax.toString()).param("version",tax.path("version").asLong()).param("old",tax.path("payload").toString()).update()==1,"Tax race");
                        audit(jdbc,"finance.channel-and-tax",key,Map.of("categories",List.of("普货","服装"),"countries",COUNTRIES,"euFixedUsd",5.83,"gbTaxUsd",0,"perKg",0));
                        audit(jdbc,"logistics.publish",versionId.toString(),Map.of("rows",52,"sourceSha256",expected.path("sha256").asText(),"channelId",channelId.toString()));
                    });
                    System.out.println("SF_BULKY_PUBLISHED_OK "+versionId);
                }
                require(guard.quoteReady(versionId),"New version unavailable");var published=acceptance.version(versionId);reconcile(published.path("payload"),expected);billing(published.path("payload"),cases);
                var rules=new LogisticsQueryService(jdbc,M).publishedRules(null,null,COUNTRIES.stream().toList(),List.of()).rules();
                var hits=rules.stream().filter(r->r.path("relations").valueStream().anyMatch(x->x.path("carrier").asText().equals("顺丰")&&x.path("channel").asText().equals("欧洲-泡货专线"))).toList();
                require(hits.size()==1&&hits.getFirst().path("billingVerified").asBoolean()&&hits.getFirst().path("prices").size()==52,"Quote projection mismatch");
                var projected=M.createArrayNode();hits.forEach(projected::add);save("published-rules",projected);save("publication-status",acceptance.status(versionId));
                String key=jdbc.sql("select rule_id::text||'::顺丰::'||code from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
                var finance=jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->M.readTree(rs.getString(1))).single();save("finance-after",finance);int policyCount=0,taxCount=0;
                for(var p:finance.path("channel-policies").path("payload"))for(var c:p.path("countryRules"))if(c.path("allowedChannels").valueStream().anyMatch(k->k.asText().equals(key))){require(Set.of("普货","服装").contains(p.path("category").asText())&&COUNTRIES.contains(c.path("country").asText()),"Unexpected authorization");policyCount++;}
                for(var c:finance.path("tax-settings").path("payload").path("countries")){
                    for(var t:c.path("channelRules"))if(t.path("key").asText().equals(key)){
                        boolean eu=c.path("country").asText().equals("欧盟");
                        require(TAX_SCOPES.contains(c.path("country").asText())&&c.path("selected").asBoolean()&&t.path("mode").asText().equals(eu?"fixed-order":"no-tax")&&t.path("amount").asDouble()==(eu?5.83:0)&&t.path("perKg").asDouble()==0&&t.path("currency").asText().equals("USD"),"Tax mismatch");taxCount++;
                    }
                    require(c.path("handlingRules").valueStream().noneMatch(t->t.path("key").asText().equals(key)),"Unexpected handling fee");
                }
                require(policyCount==52&&taxCount==2,"Finance readback mismatch");
                save("final-verification",M.createObjectNode().put("passed",true).put("channelId",channelId.toString()).put("versionId",versionId.toString()).put("rows",52).put("countries",26).put("categories","普货、服装").put("euFixedUsd",5.83).put("gbTaxUsd",0).put("perKg",0).put("channelKey",key));
                System.out.println("SF_BULKY_VERIFIED_OK "+versionId);
            }finally{factory.destroy();}
        }
    }
}

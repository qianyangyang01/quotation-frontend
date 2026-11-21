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

/** One-shot official import, review and billing acceptance. No migrations or quote recalculation. */
public class RunWanbangSpecialB {
    static final String ACTOR="codex-maintenance",REQUEST="wanbang-specialb-20261121";
    static final Path ROOT=Path.of("/maintenance");
    static final ObjectMapper M=new ObjectMapper();
    static final List<String> FIELDS=List.of("sourceSheet","sourceRow","countryCode","areaName","zoneName","weightFromKg","weightToKg","weightFromInclusive","weightToInclusive","pricePerKg","registrationFee","minChargeWeightKg","currency","pricingModel","etaMinDays","etaMaxDays");
    static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    static JsonNode read(String name)throws Exception{return M.readTree(Files.readString(ROOT.resolve(name+".json")));}
    static void save(String name,JsonNode value){try{Files.writeString(ROOT.resolve(name+".json"),value.toPrettyString());}catch(Exception e){throw new RuntimeException(e);}}
    static Set<String> strings(JsonNode values){var s=new LinkedHashSet<String>();for(var v:values)s.add(v.asText());return s;}
    static boolean sameRevision(JsonNode a,JsonNode b){return a!=null&&b!=null&&a.isIntegralNumber()&&b.isIntegralNumber()&&a.canConvertToLong()&&b.canConvertToLong()&&a.longValue()==b.longValue();}
    static JsonNode one(JsonNode values,String field,String value){var hits=values.valueStream().filter(v->v.path(field).asText().equals(value)).toList();require(hits.size()==1,"Expected one "+field+"="+value);return hits.getFirst();}
    static Set<String> countries(JsonNode e){var s=new LinkedHashSet<String>();for(var c:e.path("countries"))s.add(c.path("name").asText());return s;}
    static void audit(JdbcClient jdbc,String action,String id,Object detail){
        jdbc.sql("insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values(:id,:request,:actor,:action,'logistics',:resource,'success',cast(:detail as jsonb),now())")
            .param("id",UUID.randomUUID()).param("request",REQUEST).param("actor",ACTOR).param("action",action).param("resource",id).param("detail",M.writeValueAsString(detail)).update();
    }
    static void reconcile(JsonNode actual,JsonNode expected){
        require(actual.path("errors").asInt()==0&&actual.path("pricingReady").asBoolean(),"Invalid channel "+actual.path("issues"));
        require(actual.path("companyChannelId").equals(expected.path("companyChannelId")),"Wrong company identity");
        require(actual.path("providerName").asText().equals("万邦"),"Wrong provider");
        require(actual.path("channelName").equals(expected.path("channelName")),"Wrong channel name");
        require(actual.path("rows").size()==expected.path("rows").size(),"Wrong row count");
        for(var e:expected.path("rows")){
            var hits=actual.path("rows").valueStream().filter(r->r.path("sourceRow").equals(e.path("sourceRow"))&&r.path("sourceSheet").equals(e.path("sourceSheet"))).toList();
            require(hits.size()==1,"Wrong source row "+e.path("sourceRow"));var a=hits.getFirst();
            for(var key:FIELDS){var av=a.path(key);var ev=e.path(key);require(ev.isNumber()?av.isNumber()&&ev.decimalValue().compareTo(av.decimalValue())==0:ev.equals(av),"Mismatch "+expected.path("channelName")+" row "+e.path("sourceRow")+" "+key+" actual="+av+" expected="+ev);}
            require(Arrays.asList(a.path("sourceProductCode").asText().split("\\s+")).contains(expected.path("productCode").asText()),"Wrong source product code");
            require(a.path("quoteReady").asBoolean(),"Unready row");
            require(a.path("billingStepKg").asDouble()==0&&a.path("billingStepBands").isEmpty(),"Unexpected weight steps");
            require(!LogisticsGramPricing.applies(a)&&!LogisticsGramPricing.ceilCny(a),"Unexpected gram/currency ceiling");
        }
    }
    static void billing(JsonNode channel,JsonNode cases){
        require(cases.path("samples").size()>=2&&cases.path("rejected").size()>0,"Missing billing cases");
        var engine=new LogisticsBillingEngine(M);
        for(var c:cases.path("samples")){
            var result=engine.calculate(channel.path("rows"),c);
            require(result.path("total").decimalValue().compareTo(c.path("total").decimalValue())==0,"Billing total mismatch "+c);
            require(result.path("chargeWeightKg").decimalValue().compareTo(c.path("chargeWeightKg").decimalValue())==0,"Billing charge weight mismatch "+c);
            require(channel.path("rows").get(result.path("rowIndex").asInt()).path("sourceRow").equals(c.path("sourceRow")),"Billing selected tier mismatch "+c);
        }
        for(var c:cases.path("rejected")){boolean rejected=false;try{engine.calculate(channel.path("rows"),c);}catch(com.milano.quotation.common.AppException e){rejected=true;}require(rejected,"Expected rejection "+c);}
    }
    static void validateFinance(String key,JsonNode value){try{
        var type=Class.forName("com.milano.quotation.finance.FinanceSettingValidation");var method=type.getDeclaredMethod("validate",String.class,JsonNode.class);method.setAccessible(true);method.invoke(null,key,value);
    }catch(Exception e){throw new RuntimeException("Finance validation "+key,e);}}
    static ObjectNode exemption(String key,String note){return M.createObjectNode().put("key",key).put("mode","exempt").put("amount",0).put("currency","USD").put("perKg",0).put("source",note);}
    static String channelKey(JdbcClient jdbc,UUID channel){return jdbc.sql("select rule_id::text||'::万邦::'||code from logistics_channel where id=:id").param("id",channel).query(String.class).single();}
    static JsonNode finance(JdbcClient jdbc){return jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->M.readTree(rs.getString(1))).single();}
    static void preflightFinance(JsonNode finance,JsonNode expected,JsonNode plan){
        var policies=finance.path("channel-policies").path("payload");
        validateFinance("channel-policies",policies.deepCopy());
        validateFinance("tax-settings",finance.path("tax-settings").path("payload").deepCopy());
        var eu=one(finance.path("tax-settings").path("payload").path("countries"),"country","欧盟");
        require(eu.path("selected").asBoolean()&&eu.path("enabled").asBoolean(),"EU tax inactive");
        for(var e:expected.path("channels")){
            var categories=strings(plan.path("allowCategories").path(e.path("companyChannelId").asText()));
            require(categories.equals(strings(e.path("attributes"))),"Policy plan differs from user attributes");
            for(var category:categories){var policy=one(policies,"category",category);require(policy.path("enabled").asBoolean(),"Policy category disabled "+category);
                for(var country:countries(e))one(policy.path("countryRules"),"country",country);
            }
        }
    }
    static void updateFinance(JdbcClient jdbc,JsonNode expected,JsonNode items,JsonNode plan){
        var policy=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies' for update").query((rs,n)->M.readTree(rs.getString(1))).single();
        var tax=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='tax-settings' for update").query((rs,n)->M.readTree(rs.getString(1))).single();
        var next=policy.path("payload").deepCopy();var nextTax=tax.path("payload").deepCopy();var evidence=M.createArrayNode();
        for(var e:expected.path("channels")){
            var item=one(items,"companyChannelId",e.path("companyChannelId").asText());String key=channelKey(jdbc,UUID.fromString(item.path("channelId").asText()));
            var categories=strings(plan.path("allowCategories").path(e.path("companyChannelId").asText()));var countryNames=countries(e);int added=0;
            for(var p:next)for(var c:p.path("countryRules")){
                require(c.path("allowedChannels").valueStream().noneMatch(k->k.asText().equals(key)),"Already authorized "+key);
                if(categories.contains(p.path("category").asText())&&countryNames.contains(c.path("country").asText())){require(p.path("enabled").asBoolean(),"Policy category disabled");((ObjectNode)c).withArray("allowedChannels").add(key);added++;}
            }
            require(added==categories.size()*countryNames.size(),"Missing category/country policy "+e.path("channelName")+" count="+added);
            int euAdded=0,localAdded=0,handlingAdded=0;
            for(var value:nextTax.path("countries")){
                var c=(ObjectNode)value;String name=c.path("country").asText();
                if(name.equals("欧盟")){
                    require(c.path("selected").asBoolean()&&c.path("enabled").asBoolean(),"EU tax inactive");
                    require(c.path("channelRules").valueStream().noneMatch(r->r.path("key").asText().equals(key)),"Tax already added");
                    c.withArray("channelRules").add(exemption(key,plan.path("note").asText()));euAdded++;
                }else if(countryNames.contains(name)&&c.path("selected").asBoolean()){
                    String field=c.path("euTaxMode").asText().equals("add-handling")?"handlingRules":"channelRules";
                    require(c.path(field).valueStream().noneMatch(r->r.path("key").asText().equals(key)),"Local tax already added");
                    c.withArray(field).add(exemption(key,"2026-11-21按原表：已含关税和欧盟清关处理费，不含增值税；沿用万邦优选渠道税费配置。"));
                    if(field.equals("handlingRules"))handlingAdded++;else localAdded++;
                }
            }
            require(euAdded==1,"EU scope mismatch");
            evidence.addObject().put("channelName",e.path("channelName").asText()).put("channelKey",key).put("policyAdds",added).put("euAdds",euAdded).put("localAdds",localAdded).put("handlingAdds",handlingAdded);
        }
        validateFinance("channel-policies",next);validateFinance("tax-settings",nextTax);
        for(var pair:List.of(Map.entry("channel-policies",next),Map.entry("tax-settings",nextTax))){
            var old=pair.getKey().equals("channel-policies")?policy:tax;
            require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key=:key and version=:version and payload=cast(:old as jsonb)")
                .param("p",pair.getValue().toString()).param("key",pair.getKey()).param("version",old.path("version").asLong()).param("old",old.path("payload").toString()).update()==1,"Finance CAS race "+pair.getKey());
        }
        save("finance-additions",evidence);audit(jdbc,"finance.channel-and-tax",REQUEST,evidence);
    }
    public static void main(String[] args)throws Exception{
        require(args.length==1&&Set.of("check","import","publish","verify").contains(args[0]),"Invalid action");
        var plan=read("maintenance-plan");var expected=read("expected");var config=(ObjectNode)read("company");var cases=read("billing-cases");
        require(LogisticsSourceParser.VERSION.equals(plan.path("parserVersion").asText()),"Wrong parser");
        var ids=new LinkedHashSet<String>();for(var c:config.path("entries"))ids.add(c.path("id").asText());require(ids.size()==1&&expected.path("channels").size()==1,"Wrong scope");
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
                require(guard.activeId().toString().equals(plan.path("datasetId").asText()),"Active dataset changed");
                var logistics=new LogisticsService(repos.getRepository(LogisticsProviderRepository.class),repos.getRepository(LogisticsChannelRepository.class),repos.getRepository(LogisticsVersionRepository.class),books,guard);
                var parser=new LogisticsSourceParser(M,books);
                var directory=new CompanyChannelService(jdbc,M){@Override public ObjectNode scopeFor(UUID provider,UUID channel){var all=super.scopeFor(provider,channel);var original=all.path("entries").deepCopy();var selected=all.putArray("entries");for(var e:original)if(ids.contains(e.path("id").asText()))selected.add(e);require(selected.size()==1,"New channel scope missing");return all;}};
                var scope=new CompanyChannelScope(config);require(!scope.unrestricted(),"Unrestricted parser scope is forbidden");
                var client=new LogisticsParserClient(parser,M,env("APP_LOGISTICS_PARSER_URL"));var parsed=client.parse(bytes,expected.path("source").asText(),scope);
                require(parsed.path("channels").size()==1&&parsed.path("parserVersion").asText().equals(LogisticsSourceParser.VERSION),"Wrong parser/scope");
                for(var e:expected.path("channels")){var channel=one(parsed.path("channels"),"companyChannelId",e.path("companyChannelId").asText());reconcile(channel,e);billing(channel,one(cases.path("channels"),"channelName",e.path("channelName").asText()));}
                save("live-parsed",parsed);
                var checkFinance=finance(jdbc);preflightFinance(checkFinance,expected,plan);
                if(Set.of("check","import").contains(args[0])){
                    var current=directory.list();require(current.path("enabled").asBoolean()&&!current.path("state").path("paused").asBoolean(),"Directory unavailable");
                    require(current.path("entries").valueStream().noneMatch(e->ids.contains(e.path("id").asText())),"Target company ID already registered");
                    var merged=(tools.jackson.databind.node.ArrayNode)current.path("entries").deepCopy();for(var e:config.path("entries"))merged.add(e);
                    CompanyChannelService.validate(merged);
                }
                if(args[0].equals("check")){System.out.println("WANBANG_SPECIALB_CHECK_OK 1 channel 33 rows");return;}
                if(args[0].equals("import")){
                    require(!Files.exists(ROOT.resolve("import-result.json")),"Import already attempted; inspect evidence");
                    var before=directory.list();save("directory-before",before);
                    tx.executeWithoutResult(status->{
                        var state=directory.state(true);require(!state.path("paused").asBoolean()&&sameRevision(state.path("revision"),before.path("revision")),"Directory race");
                        var current=directory.list();var merged=current.path("entries").deepCopy();
                        require(current.path("entries").valueStream().noneMatch(e->ids.contains(e.path("id").asText())),"Already registered");
                        for(var e:config.path("entries"))((tools.jackson.databind.node.ArrayNode)merged).add(e);
                        CompanyChannelService.validate(merged);
                        for(var e:config.path("entries"))require(jdbc.sql("insert into logistics_company_channel(id,enabled,payload,updated_by) values(:id,true,cast(:p as jsonb),:actor)").param("id",UUID.fromString(e.path("id").asText())).param("p",e.toString()).param("actor",ACTOR).update()==1,"Directory insert failed");
                        require(jdbc.sql("update logistics_company_state set revision=revision+1 where singleton and revision=:revision and not paused").param("revision",before.path("revision").asLong()).update()==1,"Directory CAS race");
                        var snapshot=directory.snapshot();jdbc.sql("insert into logistics_company_revision(revision,payload,created_by) values(:r,cast(:p as jsonb),:actor)").param("r",snapshot.path("revision").asLong()).param("p",snapshot.toString()).param("actor",ACTOR).update();
                        audit(jdbc,"logistics.company-directory",REQUEST,config);
                    });
                    save("directory-after",directory.list());
                    var storage=new AssetStorageService(MinioClient.builder().endpoint(env("APP_STORAGE_ENDPOINT")).credentials(env("APP_STORAGE_ACCESS_KEY"),env("APP_STORAGE_SECRET_KEY")).build(),null,env("APP_STORAGE_BUCKET"),false);
                    var imports=new LogisticsImportService(jdbc,M,storage,parser,logistics,guard,manager,Runnable::run);
                    for(var e:Map.of("companyChannels",(Object)directory,"isolatedParser",client).entrySet()){var f=LogisticsImportService.class.getDeclaredField(e.getKey());f.setAccessible(true);f.set(imports,e.getValue());}
                    MultipartFile file=new MultipartFile(){public String getName(){return "files";}public String getOriginalFilename(){return expected.path("source").asText();}public String getContentType(){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}public boolean isEmpty(){return false;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes);}public void transferTo(java.io.File dest)throws java.io.IOException{Files.write(dest.toPath(),bytes);}};
                    var accepted=imports.uploadSelected(guard.activeId(),List.of(file),ACTOR,REQUEST,false,UUID.fromString(plan.path("providerId").asText()),null);
                    var batch=imports.get(UUID.fromString(accepted.path("id").asText()));save("import-result",batch);var results=batch.path("payload").path("results");
                    require(batch.path("status").asText().equals("completed")&&results.size()==1,"Import count/status mismatch");
                    var imported= M.createArrayNode();
                    for(var r:results){require(r.path("status").asText().equals("draft")&&r.path("errors").asInt()==0,"Import failed "+r);var channelId=UUID.fromString(r.path("channelId").asText());var company=jdbc.sql("select company_channel_id::text from logistics_company_binding where channel_id=:id").param("id",channelId).query(String.class).single();require(ids.contains(company),"Imported unrelated channel");var item=(ObjectNode)r.deepCopy();item.put("companyChannelId",company);imported.add(item);}
                    require(imported.valueStream().map(i->i.path("companyChannelId").asText()).distinct().count()==1,"Duplicate import binding");save("imported-items",imported);
                    tx.executeWithoutResult(status->audit(jdbc,"logistics.import",batch.path("id").asText(),imported));System.out.println("WANBANG_SPECIALB_IMPORT_OK "+batch.path("id"));return;
                }
                var items=read("imported-items");require(items.size()==1,"Wrong imported scope");var acceptance=new LogisticsBillingAcceptanceService(jdbc,M,guard,new LogisticsBillingEngine(M));
                if(args[0].equals("publish")){
                    save("finance-before",finance(jdbc));
                    tx.executeWithoutResult(status->{
                        for(var item:items){
                            var channelId=UUID.fromString(item.path("channelId").asText());var versionId=UUID.fromString(item.path("versionId").asText());
                            var e=one(expected.path("channels"),"companyChannelId",item.path("companyChannelId").asText());var bc=one(cases.path("channels"),"channelName",e.path("channelName").asText());
                            require(jdbc.sql("select current_version_id is null from logistics_channel where id=:id for update").param("id",channelId).query(Boolean.class).single(),"Existing published channel");
                            var v=acceptance.version(versionId);require(v.path("status").asText().equals("draft"),"Version changed");reconcile(v.path("payload"),e);billing(v.path("payload"),bc);
                            String note=plan.path("note").asText();logistics.publishReviewed(channelId,versionId,note,ACTOR,false,true,true);em.flush();
                            var approval=acceptance.status(versionId).put("reviewConfirmed",true).put("note",note).put("sourceReference",expected.path("source").asText()+" / "+e.path("sheet").asText());var samples=approval.putArray("samples");
                            for(var c:bc.path("samples")){var s=samples.addObject().put("sourceReference",e.path("sheet").asText()+" 原表第"+c.path("sourceRow").asInt()+"行，公斤价×计费重量+一次挂号费").set("expectedTotal",c.path("total"));s.putObject("input").put("country",c.path("country").asText()).set("weightKg",c.path("weightKg"));}
                            for(var c:bc.path("rejected"))samples.addObject().put("sourceReference","零重、超重或未覆盖国家拒绝").put("expectRejected",true).set("input",c);
                            acceptance.approve(versionId,approval,ACTOR);require(guard.quoteReady(versionId),"Not quote ready");
                            audit(jdbc,"logistics.publish",versionId.toString(),Map.of("rows",e.path("rows").size(),"sourceSha256",expected.path("sha256").asText(),"channelId",channelId.toString()));
                        }
                        updateFinance(jdbc,expected,items,plan);
                    });
                    System.out.println("WANBANG_SPECIALB_PUBLISHED_OK 1 channel");
                }
                var finalResult=M.createObjectNode().put("passed",true).put("rows",33).put("channels",1);var verified=finalResult.putArray("published");var projections=M.createObjectNode();var flatProjection=M.createArrayNode();
                for(var item:items){
                    var channelId=UUID.fromString(item.path("channelId").asText());var versionId=UUID.fromString(item.path("versionId").asText());var e=one(expected.path("channels"),"companyChannelId",item.path("companyChannelId").asText());
                    require(guard.quoteReady(versionId),"Published version unavailable");var published=acceptance.version(versionId);reconcile(published.path("payload"),e);billing(published.path("payload"),one(cases.path("channels"),"channelName",e.path("channelName").asText()));
                    String key=channelKey(jdbc,channelId);var categories=strings(plan.path("allowCategories").path(e.path("companyChannelId").asText()));
                    boolean firstProjection=true;
                    for(var category:categories){var codes=e.path("countries").valueStream().map(c->c.path("code").asText()).toList();var rules=new LogisticsQueryService(jdbc,M).publishedRules(null,category,codes,List.of()).rules();
                        var hits=rules.stream().filter(r->r.path("relations").valueStream().anyMatch(x->x.path("carrier").asText().equals("万邦")&&x.path("channel").asText().equals(e.path("channelName").asText()))).toList();
                        require(hits.size()==1&&hits.getFirst().path("billingVerified").asBoolean()&&hits.getFirst().path("prices").size()==e.path("rows").size(),"Projection mismatch "+e.path("channelName")+"/"+category);projections.set(e.path("companyChannelId").asText()+"/"+category,M.valueToTree(hits));
                        if(firstProjection){flatProjection.add(hits.getFirst());firstProjection=false;}
                    }
                    verified.addObject().put("companyChannelId",e.path("companyChannelId").asText()).put("channelId",channelId.toString()).put("versionId",versionId.toString()).put("channelName",e.path("channelName").asText()).put("channelKey",key).put("rows",e.path("rows").size());
                    save("acceptance-"+e.path("companyChannelId").asText(),acceptance.status(versionId));
                }
                var after=finance(jdbc);save("finance-after",after);
                for(var v:verified){var e=one(expected.path("channels"),"companyChannelId",v.path("companyChannelId").asText());String key=v.path("channelKey").asText();var categories=strings(plan.path("allowCategories").path(e.path("companyChannelId").asText()));int count=0;
                    for(var p:after.path("channel-policies").path("payload"))for(var c:p.path("countryRules"))if(c.path("allowedChannels").valueStream().anyMatch(k->k.asText().equals(key))){require(categories.contains(p.path("category").asText())&&countries(e).contains(c.path("country").asText()),"Unexpected category/country authorization");count++;}
                    require(count==categories.size()*countries(e).size(),"Missing policy authorization");var eu=one(after.path("tax-settings").path("payload").path("countries"),"country","欧盟");var rule=one(eu.path("channelRules"),"key",key);require(rule.path("mode").asText().equals("exempt")&&rule.path("amount").asDouble()==0&&rule.path("perKg").asDouble()==0,"Tax mismatch");
                    for(var country:after.path("tax-settings").path("payload").path("countries"))if(countries(e).contains(country.path("country").asText())&&country.path("selected").asBoolean()){
                        var field=country.path("euTaxMode").asText().equals("add-handling")?"handlingRules":"channelRules";var local=one(country.path(field),"key",key);
                        require(local.path("mode").asText().equals("exempt")&&local.path("amount").asDouble()==0&&local.path("perKg").asDouble()==0,"Local tax/handling mismatch "+country.path("country"));
                    }
                }
                save("published-rules-by-attribute",projections);save("published-rules",flatProjection);save("final-verification",finalResult);System.out.println("WANBANG_SPECIALB_VERIFIED_OK 1 channel 33 rows");
            }finally{factory.destroy();}
        }
    }
}

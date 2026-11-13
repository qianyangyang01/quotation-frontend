from pathlib import Path
import subprocess,tarfile,shutil
W=Path(__file__).resolve().parents[2];P=W.parents[1]/'release-packages/rongding-cosmetics-20261113';OLD=P.parent/'quotation-2026.11.11-01'
s=(OLD/'RunKuailvEurope.java').read_text(encoding='utf-8')
imports=s[:s.index('/** One-shot')]
init=s[s.index('        try(var ds='):s.index('                var directory=')]
body=r'''
public class RunRongdingCosmetics {
    static final String COMPANY="f1789e9c-769a-4f87-a751-42453252f1e1", ACTOR="codex-maintenance";
    static final Path ROOT=Path.of("/maintenance");
    static String env(String key){return Objects.requireNonNull(System.getenv(key),key);}
    static void require(boolean ok,String why){ReviewRongdingCosmetics.require(ok,why);}
    static void save(String name,JsonNode value){try{Files.writeString(ROOT.resolve(name+".json"),value.toPrettyString());}catch(Exception e){throw new RuntimeException(e);}}
    static void audit(JdbcClient jdbc,ObjectMapper mapper,String action,String id,Object detail){
        jdbc.sql("insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values(:id,'rongding-cosmetics-20261113',:actor,:action,'logistics',:resource,'success',cast(:detail as jsonb),now())")
            .param("id",UUID.randomUUID()).param("actor",ACTOR).param("action",action).param("resource",id).param("detail",mapper.writeValueAsString(detail)).update();
    }
    static JsonNode existing(JdbcClient jdbc,ObjectMapper mapper,JsonNode expected,LogisticsDatasetGuard guard){
        var id=UUID.fromString(expected.path("existingVersionId").asText());
        require(guard.quoteReady(id),"Existing channel no longer ready");
        var old=jdbc.sql("select payload::text from logistics_version where id=:id and status='published' and id=(select current_version_id from logistics_channel where id='2933dec1-eb9f-4a54-9976-95bf0538e584')").param("id",id).query((rs,n)->mapper.readTree(rs.getString(1))).single();
        ReviewRongdingCosmetics.reconcile(old,expected.path("channels").get(0));return old;
    }
    public static void main(String[] args)throws Exception {
        require(Set.of("check","import","publish","verify").contains(args[0]),"Invalid action");
        require(LogisticsSourceParser.VERSION.equals("kuailv-eu-special-2026.11.11-v18"),"Wrong parser");
        var mapper=new ObjectMapper();var expected=mapper.readTree(Files.readString(ROOT.resolve("expected.json")));
        var config=(ObjectNode)mapper.readTree(Files.readString(ROOT.resolve("company.json")));
        var cases=mapper.readTree(Files.readString(ROOT.resolve("billing-cases.json")));
        byte[] bytes=Files.readAllBytes(ROOT.resolve("source.xlsx"));
        require(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected.path("sha256").asText()),"Source hash changed");
'''+init+r'''
                var directory=new CompanyChannelService(jdbc,mapper){
                    @Override public ObjectNode scopeFor(UUID provider,UUID channel){
                        var all=super.scopeFor(provider,channel);var original=all.path("entries").deepCopy();var selected=all.putArray("entries");
                        for(var e:original)if(e.path("id").asText().equals(COMPANY))selected.add(e);
                        require(selected.size()==1,"New channel scope missing");return all;
                    }
                };
                var client=new LogisticsParserClient(parser,mapper,env("APP_LOGISTICS_PARSER_URL"));
                var parsed=client.parse(bytes,expected.path("source").asText(),new CompanyChannelScope(config));
                require(parsed.path("channels").size()==2,"Wrong parsed channel count");
                for(int i=0;i<2;i++){
                    var e=expected.path("channels").get(i);var ch=parsed.path("channels").valueStream().filter(c->c.path("companyChannelId").equals(e.path("companyChannelId"))).findFirst().orElseThrow();
                    ReviewRongdingCosmetics.reconcile(ch,e);ReviewRongdingCosmetics.billing(ch,cases.get(i));
                }
                save("live-parsed",parsed);var original=existing(jdbc,mapper,expected,guard);
                ReviewRongdingCosmetics.billing(original,cases.get(0));
                if(args[0].equals("check")){System.out.println("RONGDING_CHECK_OK 12 rows; existing six unchanged");return;}
                if(args[0].equals("import")){
                    tx.executeWithoutResult(status->{
                        var current=directory.list();require(current.path("revision").asInt()==23,"Directory changed");
                        require(current.path("entries").valueStream().noneMatch(e->e.path("id").asText().equals(COMPANY)||e.path("productCodes").toString().contains("PK00369")),"Already registered");
                        save("directory-before",current);current.withArray("entries").add(config.path("entries").get(1));
                        var result=directory.save(current,ACTOR);require(result.path("revision").asInt()==24&&result.path("entries").size()==130,"Directory mismatch");save("directory-after",result);
                        audit(jdbc,mapper,"logistics.company-directory",COMPANY,config.path("entries").get(1));
                    });
                    var storage=new AssetStorageService(MinioClient.builder().endpoint(env("APP_STORAGE_ENDPOINT")).credentials(env("APP_STORAGE_ACCESS_KEY"),env("APP_STORAGE_SECRET_KEY")).build(),null,env("APP_STORAGE_BUCKET"),false);
                    var imports=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,manager,Runnable::run);
                    for(var e:Map.of("companyChannels",(Object)directory,"isolatedParser",client).entrySet()){var f=LogisticsImportService.class.getDeclaredField(e.getKey());f.setAccessible(true);f.set(imports,e.getValue());}
                    MultipartFile file=new MultipartFile(){
                        public String getName(){return "files";}public String getOriginalFilename(){return expected.path("source").asText();}public String getContentType(){return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";}
                        public boolean isEmpty(){return false;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes);}public void transferTo(java.io.File dest)throws java.io.IOException{Files.write(dest.toPath(),bytes);}
                    };
                    var accepted=imports.uploadSelected(guard.activeId(),List.of(file),ACTOR,"rongding-cosmetics-20261113",false,null,null);
                    var batch=imports.get(UUID.fromString(accepted.path("id").asText()));save("import-result",batch);
                    var results=batch.path("payload").path("results");require(batch.path("status").asText().equals("completed")&&results.size()==1&&results.get(0).path("status").asText().equals("draft")&&results.get(0).path("errors").asInt()==0,"Import failed");
                    tx.executeWithoutResult(status->audit(jdbc,mapper,"logistics.import",batch.path("id").asText(),results));
                    System.out.println("RONGDING_IMPORT_OK "+results);return;
                }
                var batch=mapper.readTree(Files.readString(ROOT.resolve("import-result.json")));var item=batch.path("payload").path("results").get(0);
                var channelId=UUID.fromString(item.path("channelId").asText());var versionId=UUID.fromString(item.path("versionId").asText());
                var billing=new LogisticsBillingAcceptanceService(jdbc,mapper,guard,new LogisticsBillingEngine(mapper));
                if(args[0].equals("publish")){
                    var service=new LogisticsBatchPublishService(jdbc,mapper,logistics,billing,manager);var coverage=service.coverage(UUID.fromString(batch.path("id").asText()));
                    require(coverage.path("missingCount").asInt()==5,"Unexpected partial scope");save("publication-coverage",coverage);
                    LogisticsImportCoverage.requireAcknowledgment(coverage,mapper.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken",coverage.path("token").asText()));
                    tx.executeWithoutResult(status->{
                        existing(jdbc,mapper,expected,guard);var v=billing.version(versionId);
                        require(v.path("status").asText().equals("draft")&&v.path("currentVersionId").isNull(),"Version changed");
                        ReviewRongdingCosmetics.reconcile(v.path("payload"),expected.path("channels").get(1));
                        String note="2026-11-13 用户授权新增并发布容鼎PK00369(A)，按既有容鼎解析方式；原表2026-11-12生效，六档公斤价及每票处理费逐格一致，50g起计至5kg，8–12天。仅特惠商派可达邮编，以到货预报为准，绝非全邮编；尺寸及体积重等原表限制保留人工复核。PK00270原正式六档已相同，不重导、不重算历史报价。";
                        logistics.publishReviewed(channelId,versionId,note,ACTOR,false,true,true);em.flush();
                        var approval=billing.status(versionId).put("reviewConfirmed",true).put("note",note).put("sourceReference",expected.path("source").asText()+" / 容鼎美国化妆品专线 B28:I33");var samples=approval.putArray("samples");
                        for(var c:cases.get(1).path("samples")){var sample=samples.addObject().put("sourceReference","原表D"+c.path("sourceRow").asInt()+":F"+c.path("sourceRow").asInt()+"，50g起计，公斤价加每票处理费").set("expectedTotal",c.path("total"));sample.putObject("input").put("country","US").set("weightKg",c.path("weightKg"));}
                        for(var c:cases.get(1).path("rejected"))samples.addObject().put("sourceReference","超重、零重、非美国或原表未覆盖重量拒绝").put("expectRejected",true).set("input",c);
                        billing.approve(versionId,approval,ACTOR);require(guard.quoteReady(versionId),"Not ready");
                        var finance=jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->mapper.readTree(rs.getString(1))).single();save("finance-before",finance);
                        var policy=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies' for update").query((rs,n)->mapper.readTree(rs.getString(1))).single();
                        var tax=jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='tax-settings' for update").query((rs,n)->mapper.readTree(rs.getString(1))).single();
                        require(policy.path("version").asInt()==65&&tax.path("version").asInt()==43,"Finance changed");
                        String key=jdbc.sql("select rule_id::text||'::容鼎::'||code from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
                        var next=policy.path("payload").deepCopy();int added=0;
                        for(var p:next)for(var c:p.path("countryRules"))if(p.path("category").asText().equals("化妆品")&&c.path("country").asText().equals("美国")){
                            require(p.path("enabled").asBoolean()&&c.path("allowedChannels").valueStream().anyMatch(x->x.asText().equals("561::容鼎::C-f928fd7c894686ff74e8")),"Existing cosmetics policy absent");
                            require(c.path("allowedChannels").valueStream().noneMatch(x->x.asText().equals(key)),"Already authorized");((ObjectNode)c).withArray("allowedChannels").add(key);added++;
                        }
                        require(added==1,"Wrong policy count");com.milano.quotation.finance.ValidateKuailvFinance.validate(next);
                        var nextTax=tax.path("payload").deepCopy();int taxAdded=0;
                        for(var country:nextTax.path("countries"))if(country.path("country").asText().equals("美国")){
                            require(country.path("channelRules").valueStream().noneMatch(x->x.path("key").asText().equals(key)),"Already taxed");
                            ((ObjectNode)country).withArray("channelRules").addObject().put("key",key).put("mode","exempt").put("amount",0).put("currency","USD").put("perKg",0).put("source","容鼎11月12日价表PK00369(A)：美国标准DDP专线-特惠化妆品；沿用容鼎DDP包税配置，商派邮编限制。");taxAdded++;
                        }
                        require(taxAdded==1,"Wrong tax count");com.milano.quotation.finance.ValidateKuailvFinance.validateTax(nextTax);
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='channel-policies' and version=65 and payload=cast(:old as jsonb)").param("p",next.toString()).param("old",policy.path("payload").toString()).update()==1,"Policy race");
                        require(jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key='tax-settings' and version=43 and payload=cast(:old as jsonb)").param("p",nextTax.toString()).param("old",tax.path("payload").toString()).update()==1,"Tax race");
                        save("finance-prepared",mapper.createObjectNode().put("channelKey",key).set("policy",next).set("tax",nextTax));
                        audit(jdbc,mapper,"finance.channel-and-tax",key,Map.of("category","化妆品","country","美国","taxMode","exempt","policyVersion",66,"taxVersion",44));
                        audit(jdbc,mapper,"logistics.publish",versionId.toString(),Map.of("rows",6,"sourceSha256",expected.path("sha256").asText(),"channelId",channelId.toString()));
                    });
                    System.out.println("RONGDING_PUBLISHED_OK "+versionId);
                }
                require(guard.quoteReady(versionId),"New version unavailable");existing(jdbc,mapper,expected,guard);
                var published=billing.version(versionId);ReviewRongdingCosmetics.reconcile(published.path("payload"),expected.path("channels").get(1));ReviewRongdingCosmetics.billing(published.path("payload"),cases.get(1));
                var rules=new LogisticsQueryService(jdbc,mapper).publishedRules(null,"化妆品",List.of("US"),List.of()).rules();
                var hits=rules.stream().filter(r->r.path("relations").valueStream().anyMatch(x->x.path("carrier").asText().equals("容鼎")&&(x.path("channel").asText().equals("美国纯商派化妆品专线")||x.path("channel").asText().equals("美国标准DDP专线-特惠化妆品A(SP)")))).toList();
                require(hits.size()==2&&hits.stream().allMatch(r->r.path("billingVerified").asBoolean()&&r.path("prices").size()==6),"Quote projection mismatch");
                var projected=mapper.createArrayNode();hits.forEach(projected::add);save("published-rules",projected);save("publication-status",billing.status(versionId));
                var prepared=mapper.readTree(Files.readString(ROOT.resolve("finance-prepared.json")));
                var finance=jdbc.sql("select jsonb_object_agg(setting_key,to_jsonb(f))::text from finance_setting f").query((rs,n)->mapper.readTree(rs.getString(1))).single();
                require(finance.path("channel-policies").path("payload").equals(prepared.path("policy"))&&finance.path("tax-settings").path("payload").equals(prepared.path("tax")),"Finance readback mismatch");save("finance-after",finance);
                save("final-verification",mapper.createObjectNode().put("passed",true).put("existingUnchanged",true).put("channelId",channelId.toString()).put("versionId",versionId.toString()).put("newRows",6).put("channelKey",prepared.path("channelKey").asText()));
                System.out.println("RONGDING_VERIFIED_OK "+versionId);
            }finally{factory.destroy();}
        }
    }
}
'''
(P/'RunRongdingCosmetics.java').write_text(imports+body,encoding='utf-8')
shutil.copyfile(W/'scripts/logistics/ReviewRongdingCosmetics.java',P/'ReviewRongdingCosmetics.java')
shutil.copyfile(OLD/'ValidateKuailvFinance.java',P/'ValidateKuailvFinance.java')
cp=str(OLD/'compile-app/BOOT-INF/classes')+';'+str(OLD/'compile-app/BOOT-INF/lib/*')
subprocess.run(['C:/Users/25490/.cache/codex-runtimes/temurin21/jdk-21.0.11+10/bin/javac.exe','-encoding','UTF-8','-cp',cp,'-d',str(P/'runtime'),str(P/'RunRongdingCosmetics.java'),str(P/'ReviewRongdingCosmetics.java'),str(P/'ValidateKuailvFinance.java')],check=True)
with tarfile.open(P/'runtime.tar.gz','w:gz') as t:t.add(P/'runtime',arcname='runtime')
s=(OLD/'run-kuailv-maintenance.py').read_text(encoding='utf-8').replace('kuailv-eu-special-20261111','rongding-cosmetics-20261113').replace("'check','import','publish','finance','verify'","'check','import','publish','verify'").replace('RunKuailvEurope','RunRongdingCosmetics').replace("if line.startswith('KUAILV_')","if line.startswith('RONGDING_')")
(P/'run-maintenance.py').write_text(s,encoding='utf-8')
print('RONGDING_MAINTENANCE_COMPILED')

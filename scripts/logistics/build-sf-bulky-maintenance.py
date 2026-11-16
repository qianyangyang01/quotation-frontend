from pathlib import Path
import json,subprocess,shutil
W=Path(__file__).resolve().parents[2];P=W.parents[1]/'release-packages/sf-europe-bulky-20261116'
s=(W/'scripts/logistics/RunBaizhou.java').read_text(encoding='utf-8')
s=s.replace('RunBaizhou','RunSfBulky').replace('BAIZHOU_','SF_BULKY_').replace('baizhou-bz503-20261116','sf-europe-bulky-20261116').replace('baizhou-bz503-2026.11.16-v20','sf-europe-bulky-2026.11.16-v22')
s=s.replace('        byte[] bytes=', '        var baseline=M.readTree(Files.readString(ROOT.resolve("finance-baseline.json")));\n        byte[] bytes=')
s=s.replace('fe84a955-c584-4ed5-9b0e-1c79957c1c83','10bd9c26-404d-4f4f-bb12-dd86b832f968')
e=json.loads((P/'expected.json').read_text(encoding='utf-8'))
countries=sorted({r['areaName'] for r in e['rows']})
s=s.replace('static final Set<String> COUNTRIES=Set.of("美国");','static final Set<String> COUNTRIES=Set.of('+','.join(json.dumps(c,ensure_ascii=False) for c in countries)+');')
s=s.replace('static final Set<String> TAX_SCOPES=Set.of("美国");','static final Set<String> TAX_SCOPES=Set.of("欧盟","英国");')
start=s.index('    static void reconcile(');end=s.index('    public static void main',start)
s=s[:start]+'''    static void reconcile(JsonNode actual,JsonNode expected){ReviewSfBulky.reconcile(actual,expected);}
    static void billing(JsonNode channel,JsonNode cases){ReviewSfBulky.billing(channel,cases);}
'''+s[end:]
s=s.replace('SF_BULKY_CHECK_OK 7 US rows','SF_BULKY_CHECK_OK 52 rows / 26 countries')
s=s.replace('current.path("revision").asInt()==24','current.path("revision").asInt()==config.path("revision").asInt()')
s=s.replace('||e.path("productCodes").toString().contains("BZ503")','||e.path("providerName").asText().equals("顺丰")&&e.path("channelName").asText().equals("欧洲-泡货专线")')
s=s.replace('result.path("revision").asInt()==25&&result.path("entries").size()==131','result.path("revision").asInt()==config.path("revision").asInt()+1&&result.path("entries").size()==current.path("entries").size()')
start=s.index('                        String note=');end=s.index('                        logistics.publishReviewed',start)
s=s[:start]+'''                        String note="2026-11-16 用户确认新增顺丰欧洲-泡货专线，普货、服装。欧盟固定5.83美元/单，英国无税；每公斤税费为0，不叠加其他顺丰渠道税费或处理费。26国52档104个价格字段与原表一致，克重向上进位后匹配档位，公斤运费加一次挂号费，人民币分四舍五入。保留一票一件、材积/18000与实重取大及尺寸、偏远邮编人工复核。历史报价和审核不修改。";
'''+s[end:]
s=s.replace(' / BZ-欧美服装专线挂号 A3:F10',' / 欧洲-泡货专线 B3:I55；C62:F63')
s=s.replace('"原表C"+c.path("sourceRow").asInt()+":F"+c.path("sourceRow").asInt()+"；用户确认1g和人民币元进位"','"原表G"+c.path("sourceRow").asInt()+":I"+c.path("sourceRow").asInt()+"；C62/C63精确至克"')
start=s.index('require(policy.path("version").asInt()==66');end=s.index('                    });',start)
s=s[:start]+'''
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
'''+s[end:]
start=s.index('                var rules=new LogisticsQueryService');end=s.index('            }finally{factory.destroy();}',start)
s=s[:start]+'''                var rules=new LogisticsQueryService(jdbc,M).publishedRules(null,null,COUNTRIES.stream().toList(),List.of()).rules();
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
'''+s[end:]
s=s.replace('require(coverage.path("missingCount").asInt()==0,"Unexpected missing company channels");', '''var excluded=M.readTree(Files.readString(ROOT.resolve("expected-excluded-channels.json")));
                    require(coverage.path("missingCount").asInt()==5&&coverage.path("missingChannels").equals(excluded.path("missingChannels")),"Unchanged existing SF channels differ from reviewed scope");
                    LogisticsImportCoverage.requireAcknowledgment(coverage,M.createObjectNode().put("partialUpdateConfirmed",true).put("coverageToken",coverage.path("token").asText()));''')
assert '百州' not in s and 'BZ503' not in s
(W/'scripts/logistics/RunSfBulky.java').write_text(s,encoding='utf-8')
shutil.copyfile(W.parents[1]/'release-packages/quotation-2026.11.11-01/ValidateKuailvFinance.java',P/'ValidateKuailvFinance.java')
b=json.loads((W/'artifacts/sf-europe-bulky-20261116/baseline.json').read_text(encoding='utf-8'))
(P/'finance-baseline.json').write_text(json.dumps({f['setting_key']:f for f in b['finance']},ensure_ascii=False,indent=2),encoding='utf-8')
cp=str(W/'backend/target/classes')+';'+str(P/'compile-app/BOOT-INF/lib/*')
subprocess.run(['C:/Users/25490/.cache/codex-runtimes/temurin21/jdk-21.0.11+10/bin/javac.exe','-encoding','UTF-8','-cp',cp,'-d',str(P/'runtime'),str(W/'scripts/logistics/RunSfBulky.java'),str(W/'scripts/logistics/ReviewSfBulky.java'),str(P/'ValidateKuailvFinance.java')],check=True)
print('SF_BULKY_MAINTENANCE_COMPILED')

import runpy,json
from pathlib import Path
a=runpy.run_path(str(Path(__file__).with_name('audit-sf-bulky.py')))
b=a['sql']("""SELECT json_build_object('channels',(select json_agg(t) from (select c.rule_id,c.code,c.payload,v.payload as version from logistics_channel c join logistics_provider p on p.id=c.provider_id left join logistics_version v on v.id=c.current_version_id where p.payload->>'name'='顺丰' and c.dataset_id='bd07399a-1e05-4886-b3f6-699ee9c64919' and c.archived_at is null) t),'finance',(select json_agg(f) from finance_setting f where setting_key in ('tax-settings','exchange-rate')))""")
(a['OUT']/'tax-readonly-clarification.json').write_text(json.dumps(b,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps([dict(id=c['rule_id'],name=c['payload'].get('name'),attribute=c['version'].get('logisticsAttribute'),countries=sorted({r['countryCode'] for r in c['version']['rows']})) for c in b['channels']],ensure_ascii=False))
for f in b['finance']:
 print(f['setting_key'],f['version'])
 if f['setting_key']=='exchange-rate': print(json.dumps(f['payload'],ensure_ascii=False))
 else:
  for c in f['payload']['countries']:
   sf=[r for r in c.get('channelRules',[]) if '::顺丰::' in r['key']]
   handling=[r for r in c.get('handlingRules',[]) if '::顺丰::' in r['key']]
   if sf or handling or c['country']=='英国':print(json.dumps(dict(country=c['country'],selected=c['selected'],enabled=c.get('enabled'),fixedFeeUsd=c.get('fixedFeeUsd'),euTaxMode=c.get('euTaxMode'),rules=sf,handling=handling),ensure_ascii=False))

import runpy,json
a=runpy.run_path(str(__file__).replace('inspect-rongding-current.py','audit-rongding-cosmetics.py'))
q="SELECT json_build_object('state',(select to_jsonb(s) from logistics_company_state s),'directory',(select json_agg(payload) from logistics_company_channel),'current',(select to_jsonb(v) from logistics_version v where id='fb95089f-9b4f-4fca-b59d-7914f49683da'))"
t=a['sql'](q)
(a['OUT']/'directory-version.json').write_text(t,encoding='utf-8')
d=json.loads(t)
print(json.dumps(dict(state=d['state'],directory=[e for e in d['directory'] if e['providerName']=='容鼎'],current=d['current']['payload']),ensure_ascii=False))
b=json.loads((a['OUT']/'baseline.json').read_text(encoding='utf-8'))
for f in b['finance']:
    print('FINANCE',f['setting_key'],f['version'])
    if f['setting_key']=='channel-policies':
        for p in f['payload']:
            for c in p['countryRules']:
                if c['country']=='美国' and ('化妆品' in p['category'] or any('561::' in k for k in c['allowedChannels'])):print(json.dumps(dict(category=p['category'],country=c),ensure_ascii=False))
    if f['setting_key']=='tax-settings':
        for c in f['payload']['countries']:
            if c['country']=='美国': print(json.dumps(c,ensure_ascii=False))

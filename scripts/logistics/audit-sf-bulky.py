from pathlib import Path
import json, hashlib, subprocess, sys, zipfile, xml.etree.ElementTree as E
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/sf-europe-bulky-20261116'
OUT.mkdir(parents=True,exist_ok=True)
SOURCE=Path('C:/Users/25490/Desktop/顺丰国际电商产品价格表(20261116）.xlsx')
SSH=['ssh','-i','C:/Users/25490/.ssh/ahmln_deploy_ed25519','-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ConnectTimeout=15','deploy@8.210.66.119']
def sql(query):
    r=subprocess.run(SSH+['docker exec -i quotation-prod-quotation-postgres-1 psql -X -qAt -U quotation_app -d quotation_prod -v ON_ERROR_STOP=1'],input=("BEGIN READ ONLY; SET LOCAL statement_timeout='30s';"+query+';COMMIT;').encode(),capture_output=True,check=True)
    return json.loads(r.stdout.decode())
if __name__=='__main__':
    z=zipfile.ZipFile(SOURCE);ns={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
    ss=[''.join(t.itertext()) for t in E.fromstring(z.read('xl/sharedStrings.xml'))]
    rel={e.attrib['Id']:e.attrib['Target'] for e in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sh=[s for s in E.fromstring(z.read('xl/workbook.xml')).find('m:sheets',ns) if s.attrib['name']=='欧洲-泡货专线'][0]
    x=E.fromstring(z.read('xl/'+rel[sh.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']]))
    cells={}
    for c in x.findall('m:sheetData/m:row/m:c',ns):
        v=c.findtext('m:v',default=None,namespaces=ns)
        if v is not None:
            assert c.find('m:f',ns) is None or c.attrib['r']=='B2', 'Review formula '+c.attrib['r']
            cells[c.attrib['r']]=ss[int(v)] if c.attrib.get('t')=='s' else v if c.attrib.get('t')=='str' else float(v)
    evidence=dict(source=SOURCE.name,sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),sheet=sh.attrib['name'],cells=cells,merges=[c.attrib['ref'] for c in x.findall('m:mergeCells/m:mergeCell',ns)])
    (OUT/'source-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    b=sql("SELECT json_build_object('state',(select to_jsonb(s) from logistics_company_state s),'directory',(select json_agg(payload) from logistics_company_channel),'channels',(select json_agg(t) from (select c.*,p.payload as provider,v.payload as version from logistics_channel c join logistics_provider p on p.id=c.provider_id left join logistics_version v on v.id=c.current_version_id where p.payload->>'name'='顺丰') t),'finance',(select json_agg(f) from finance_setting f))")
    (OUT/'baseline.json').write_text(json.dumps(b,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(state=b['state'],directory=[e for e in b['directory'] if e['providerName']=='顺丰'],channels=[{k:v for k,v in c.items() if k not in ['version','provider','payload']} for c in b['channels']],finance=[(f['setting_key'],f['version']) for f in b['finance']]),ensure_ascii=False))
    for f in b['finance']:
        if f['setting_key']=='tax-settings':
            for c in f['payload']['countries']:
                sf=[r for r in c.get('channelRules',[]) if '::顺丰::' in r['key']]
                if sf: print(json.dumps(dict(country=c['country'],selected=c['selected'],rules=sf),ensure_ascii=False))
    print(json.dumps({k:v for k,v in cells.items() if k.startswith(('B6','C6','F6','I6'))},ensure_ascii=False))

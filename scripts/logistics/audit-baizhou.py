from pathlib import Path
import json, hashlib, subprocess
import openpyxl

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/baizhou-bz503-20261116'
OUT.mkdir(parents=True,exist_ok=True)
SOURCE=Path('C:/Users/25490/Desktop/YB992-百洲最新报价-VVVVVIP-11.16(1).xlsx')
SSH=['ssh','-i','C:/Users/25490/.ssh/ahmln_deploy_ed25519','-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ConnectTimeout=15','deploy@8.210.66.119']
def sql(query):
    result=subprocess.run(SSH+['docker exec -i quotation-prod-quotation-postgres-1 psql -X -qAt -U quotation_app -d quotation_prod -v ON_ERROR_STOP=1'],input=("BEGIN READ ONLY; SET LOCAL statement_timeout='30s';"+query+';COMMIT;').encode(),capture_output=True,check=True)
    return json.loads(result.stdout.decode())
if __name__=='__main__':
    w=openpyxl.load_workbook(SOURCE,data_only=True)
    s=w['BZ-欧美服装专线挂号']
    evidence=dict(source=SOURCE.name,sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),sheet=s.title,rows=[{c.coordinate:c.value for c in row if c.value is not None} for row in s],merges=[str(m) for m in s.merged_cells.ranges])
    (OUT/'source-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    baseline=sql("SELECT json_build_object('state',(select to_jsonb(s) from logistics_company_state s),'directory',(select json_agg(payload) from logistics_company_channel),'channels',(select json_agg(t) from (select c.*,p.payload as provider,v.payload as version from logistics_channel c join logistics_provider p on p.id=c.provider_id left join logistics_version v on v.id=c.current_version_id where p.payload->>'name' in ('百洲','百州')) t),'finance',(select json_agg(f) from finance_setting f))")
    (OUT/'baseline.json').write_text(json.dumps(baseline,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(state=baseline['state'],directory=[e for e in baseline['directory'] if '百' in e['providerName']],channels=baseline['channels'],finance=[(f['setting_key'],f['version']) for f in baseline['finance']]),ensure_ascii=False))

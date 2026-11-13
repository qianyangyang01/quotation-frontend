from pathlib import Path
import json, hashlib, subprocess, sys
import openpyxl

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/rongding-cosmetics-20261113'
OUT.mkdir(parents=True,exist_ok=True)
SOURCE=Path('C:/Users/25490/Desktop/容鼎供应链&合肥松创11月12日生效最新价格.xlsx')
SSH=['ssh','-i','C:/Users/25490/.ssh/ahmln_deploy_ed25519','-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','ConnectTimeout=15','deploy@8.210.66.119']
def sql(query):
    p=subprocess.run(SSH+['docker exec -i quotation-prod-quotation-postgres-1 psql -X -qAt -U quotation_app -d quotation_prod -v ON_ERROR_STOP=1'],input=('BEGIN READ ONLY; SET LOCAL statement_timeout=\'30s\';'+query+';COMMIT;').encode(),capture_output=True,check=True)
    return p.stdout.decode('utf-8')
if __name__=='__main__':
    w=openpyxl.load_workbook(SOURCE,data_only=True)
    s=w['容鼎美国化妆品专线 ']
    rows=[{c.coordinate:c.value for c in row if c.value is not None} for row in s]
    evidence=dict(source=SOURCE.name,sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),sheet=s.title,rows=rows,merges=[str(m) for m in s.merged_cells.ranges])
    (OUT/'source-evidence.json').write_text(json.dumps(evidence,default=str,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(evidence,default=str,ensure_ascii=False))
    result=sql("SELECT json_build_object('manifest','see-server','schema',(SELECT json_agg(t) FROM (SELECT table_name,column_name,data_type FROM information_schema.columns WHERE table_schema='public' AND (table_name LIKE 'company%channel%' OR table_name IN ('finance_setting','logistics_channel','logistics_provider','logistics_version','logistics_dataset'))) t),'channels',(SELECT json_agg(t) FROM (SELECT c.*,p.payload as provider FROM logistics_channel c JOIN logistics_provider p ON p.id=c.provider_id WHERE p.payload->>'name'='容鼎') t),'finance',(SELECT json_agg(f) FROM finance_setting f))")
    (OUT/'baseline.json').write_text(result,encoding='utf-8')
    data=json.loads(result)
    print(json.dumps({k:v for k,v in data.items() if k!='finance'},ensure_ascii=False))


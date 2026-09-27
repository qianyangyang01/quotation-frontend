#!/usr/bin/env python3
"""Read the authorized old Shimo table and prepare a tax-only reconciliation.

Credentials stay in server process memory. No Shimo write endpoint is used.
"""
import argparse, collections, csv, datetime, hashlib, io, json, re, subprocess, time
import urllib.request, urllib.parse, urllib.error
from pathlib import Path

FILE_GUID = 'm8AZM6oxdKSKlWkb'
FILE_NAME = '国际站2026询价'

def psql(sql):
    r = subprocess.run(['docker','exec','-i','quotation-prod-quotation-postgres-1','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','quotation_app','-d','quotation_prod'],input=sql.encode(),capture_output=True)
    if r.returncode: raise RuntimeError(r.stderr.decode())
    return r.stdout.decode()

def col(i):
    out=''
    while i>=0: out=chr(65+i%26)+out; i=i//26-1
    return out

def sku(v): return re.sub(r'\s+','',str(v or '')).upper()

def tax(v):
    if v is None or isinstance(v,bool) or not str(v).strip(): return None
    raw=str(v).strip().replace('％','%')
    try: n=float(raw.rstrip('%'))
    except ValueError: return None
    if raw.endswith('%') or n>1:n/=100
    return n if 0<=n<=1 else None

def invoice(v):
    v=str(v or '').strip()
    if re.fullmatch(r'普票(?:\d+(?:\.\d+)?%?)?',v):return '普票'
    if re.fullmatch(r'专票(?:\d+(?:\.\d+)?%?)?',v):return '专票'
    return v

def prepare(folder):
    folder.mkdir(parents=True,exist_ok=False,mode=0o700)
    raw=psql("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT to_jsonb(p) FROM purchase_product p WHERE payload->>'dataSource'='legacy_2026' AND payload->'taxPoint'='0'::jsonb AND btrim(payload->>'invoiceType')='待确认' ORDER BY sku; COMMIT;")
    (folder/'before.jsonl').write_text(raw,encoding='utf-8')
    targets=[json.loads(s) for s in raw.splitlines() if s]
    env=dict(v.split('=',1) for v in json.loads(subprocess.check_output(['docker','inspect','quotation-prod-quotation-backend-1']))[0]['Config']['Env'] if '=' in v)
    token=env.get('APP_SHIMO_TOKEN','')
    if not token:raise RuntimeError('Shimo connection is not configured')
    def values(sheet,area):
        for attempt in range(3):
            try:
                url='https://shimo.im/lizard-api/files/'+FILE_GUID+'/sheets/values?'+urllib.parse.urlencode({'range':"'"+sheet.replace("'","''")+"'!"+area})
                req=urllib.request.Request(url,headers={'Authorization':'Bearer '+token})
                with urllib.request.urlopen(req,timeout=45) as r: obj=json.load(r)
                if not isinstance(obj.get('values'),list):raise RuntimeError('Unexpected values response')
                return obj['values']
            except (TimeoutError,urllib.error.URLError):
                if attempt==2:raise
                time.sleep(2+attempt*2)
    def cell(row,i):return row[i] if i<len(row) else None
    def index(sheet,sc):
        result=[]
        for start in range(2,20002,5000):
            page=values(sheet,f'{sc}{start}:{sc}{start+4999}')
            result.extend(page)
            if len(page)<5000:return result
        raise RuntimeError('SKU scan limit reached')
    source=[]; failures=[]
    for sheet in sorted({r['payload'].get('sourceSheet','') for r in targets}):
        if not sheet:continue
        try:
            header=values(sheet,'A1:AZ1')[0]
            h=[re.sub(r'\s+','',str(v or '')) for v in header]
            indexes={key:h.index(key) for key in ('SKU','票点','票类型')}
            sc=col(indexes['SKU']); lo=min(indexes['票点'],indexes['票类型']); hi=max(indexes['票点'],indexes['票类型'])
            idx=index(sheet,sc)
            pairs=[]; page_size=5000//(hi-lo+1)
            for offset in range(0,len(idx),page_size):
                size=min(page_size,len(idx)-offset)
                page=values(sheet,f'{col(lo)}{offset+2}:{col(hi)}{offset+size+1}')
                pairs.extend(page+[[]]*(size-len(page)))
            check=index(sheet,sc)
            if idx!=check:raise RuntimeError('Rows changed during read')
            for i,row in enumerate(idx):
                key=sku(cell(row,0))
                if not key:continue
                vals=pairs[i] if i<len(pairs) else []
                source.append({'sheet':sheet,'row':i+2,'sku':key,'taxPointRaw':cell(vals,indexes['票点']-lo),'invoiceTypeRaw':cell(vals,indexes['票类型']-lo)})
            print(json.dumps({'sheet':sheet,'rows':len(idx),'sourceRows':len(source)},ensure_ascii=False),flush=True)
            (folder/'source-partial.json').write_text(json.dumps(source,ensure_ascii=False),encoding='utf-8')
        except Exception as e:
            failures.append({'sheet':sheet,'reason':str(e)})
            print(json.dumps({'sheet':sheet,'error':str(e)},ensure_ascii=False),flush=True)
    bykey=collections.defaultdict(list)
    for r in source:bykey[(r['sheet'],r['sku'])].append(r)
    report=[]; changes=[]
    known={'普票','专票','不开票','收据','增值税专用发票','增值税普通发票'}
    for old in targets:
        p=old['payload']; matches=bykey[(p.get('sourceSheet',''),old['sku'])]
        status='missing' if not matches else 'duplicate' if len(matches)>1 else 'matched'
        entry={'sku':old['sku'],'sheet':p.get('sourceSheet'),'oldRow':p.get('sourceRow'),'status':status}
        if status=='matched':
            r=matches[0]; point=tax(r['taxPointRaw']); kind=invoice(r['invoiceTypeRaw']);entry.update(source=r,taxPoint=point,invoiceType=kind)
            if point is None:entry['status']='invalid-tax'
            elif kind not in known:entry['status']='pending-type' if kind=='待确认' else 'invalid-type'
            else:
                entry['status']='change';changes.append({'id':old['id'],'sku':old['sku'],'version':old['version'],'source':r,'taxPoint':point,'invoiceType':kind})
        report.append(entry)
    for name,data in [('source.json',source),('report.json',report),('changes.json',changes)]:
        (folder/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    summary={'fileGuid':FILE_GUID,'fileName':FILE_NAME,'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'targets':len(targets),'sourceRows':len(source),'counts':dict(collections.Counter(r['status'] for r in report)),'failures':failures}
    summary['sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.iterdir() if p.is_file()}
    (folder/'manifest.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8'); print(json.dumps(summary,ensure_ascii=False),flush=True)

def apply(folder,expected,rollback=False):
    manifest=json.loads((folder/'manifest.json').read_text())
    if manifest['fileGuid']!=FILE_GUID or manifest['failures']:raise RuntimeError('Incomplete or wrong source')
    for name,digest in manifest['sha256'].items():
        if hashlib.sha256((folder/name).read_bytes()).hexdigest()!=digest:raise RuntimeError('Snapshot hash mismatch: '+name)
    changes=json.loads((folder/'changes.json').read_text())
    if len(changes)!=expected or expected<=0:raise RuntimeError('Unexpected change count')
    before={r['id']:r for r in map(json.loads,(folder/'before.jsonl').read_text().splitlines())}
    after={r['id']:r for r in map(json.loads,(folder/'after.jsonl').read_text().splitlines())} if rollback else {}
    if not rollback:
        if not (folder/'full-before.dump').is_file() or not (folder/'full-before.list').is_file():raise RuntimeError('Verified database backup required')
        if (datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(manifest['capturedAt'])).total_seconds()>3600:raise RuntimeError('Source snapshot older than one hour; prepare again')
    records=[]
    for c in changes:
        b=before[c['id']]
        if b['sku']!=c['sku'] or b['version']!=c['version'] or b['payload'].get('taxPoint')!=0 or b['payload'].get('invoiceType','').strip()!='待确认':raise RuntimeError('Invalid target')
        if tax(c['source']['taxPointRaw'])!=c['taxPoint'] or invoice(c['source']['invoiceTypeRaw'])!=c['invoiceType']:raise RuntimeError('Source mapping mismatch')
        records.append({'before':b,'expected':after[c['id']] if rollback else b,'change':c})
    buf=io.StringIO(newline='');w=csv.writer(buf,lineterminator='\n')
    for r in records:w.writerow([json.dumps(r,ensure_ascii=False,separators=(',',':'))])
    batch=folder.name
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',batch):raise RuntimeError('Invalid batch name')
    operation='石墨旧表票点核对回退' if rollback else '石墨旧表票点核对'
    action='purchase.legacy-invoice-rollback' if rollback else 'purchase.legacy-invoice-sync'
    new_payload="(p.payload - ARRAY['taxPoint','invoiceType','taxDifference']) || (SELECT jsonb_object_agg(key,value) FROM jsonb_each(t.doc->'before'->'payload') WHERE key IN ('taxPoint','invoiceType','taxDifference'))" if rollback else "p.payload || jsonb_build_object('taxPoint',t.doc->'change'->'taxPoint','invoiceType',t.doc->'change'->'invoiceType','taxDifference',t.doc->'change'->'invoiceType')"
    sql="BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='60s';\nCREATE TEMP TABLE targets(doc jsonb) ON COMMIT DROP;\nCOPY targets(doc) FROM STDIN WITH(FORMAT csv);\n"+buf.getvalue()+"\\.\n"
    sql+=f"""
SELECT p.id FROM purchase_product p JOIN targets t ON p.id=(t.doc->'expected'->>'id')::uuid ORDER BY p.id FOR UPDATE OF p;
LOCK TABLE quotation_record IN SHARE MODE;
CREATE TEMP TABLE quotes_before ON COMMIT DROP AS SELECT id,to_jsonb(q) AS doc FROM quotation_record q;
DO $$ BEGIN
 IF current_database()<>'quotation_prod' THEN RAISE EXCEPTION 'Wrong database'; END IF;
 IF (SELECT count(DISTINCT doc->'expected'->>'id') FROM targets)<>{expected} THEN RAISE EXCEPTION 'Target count mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM audit_log WHERE action='{action}' AND resource_id='{batch}') THEN RAISE EXCEPTION 'Batch already applied'; END IF;
 IF EXISTS(SELECT 1 FROM targets t LEFT JOIN purchase_product p ON p.id=(t.doc->'expected'->>'id')::uuid WHERE p.id IS NULL OR to_jsonb(p) IS DISTINCT FROM t.doc->'expected') THEN RAISE EXCEPTION 'Concurrent purchase edit; prepare again'; END IF;
END $$;
UPDATE purchase_product p SET payload={new_payload},version=p.version+1,updated_at=clock_timestamp()
FROM targets t WHERE p.id=(t.doc->'expected'->>'id')::uuid;
INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
SELECT gen_random_uuid(),'{batch}','shimo-tax-sync','purchase.maintenance','purchase-product-history',p.id::text,'success',
jsonb_build_object('actorName','石墨旧表票点核对','sku',p.sku,'operation','{operation}','batch','{batch}',
 'fileGuid','{FILE_GUID}','fileName','{FILE_NAME}','source',t.doc->'change'->'source',
 'changes',(SELECT jsonb_agg(jsonb_build_object('field',key,'label',CASE key WHEN 'taxPoint' THEN '票点' ELSE '票类型' END,'before',t.doc->'expected'->'payload'->key,'after',p.payload->key)) FROM unnest(ARRAY['taxPoint','invoiceType']) AS key WHERE t.doc->'expected'->'payload'->key IS DISTINCT FROM p.payload->key)),clock_timestamp()
FROM purchase_product p JOIN targets t ON p.id=(t.doc->'expected'->>'id')::uuid;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM quotes_before b FULL JOIN quotation_record q ON q.id=b.id WHERE b.doc IS DISTINCT FROM to_jsonb(q)) THEN RAISE EXCEPTION 'Historical quotation changed'; END IF;
 IF (SELECT count(*) FROM audit_log WHERE request_id='{batch}' AND resource_type='purchase-product-history' AND detail->>'operation'='{operation}')<>{expected} THEN RAISE EXCEPTION 'Audit count mismatch'; END IF;
END $$;
INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
VALUES(gen_random_uuid(),'{batch}','shimo-tax-sync','{action}','purchase-invoice-batch','{batch}','success',jsonb_build_object('count',{expected},'sourceSha256','{manifest['sha256']['source.json']}','historicalQuotesUnchanged',true),clock_timestamp());
SELECT 'after|'||to_jsonb(p)::text FROM purchase_product p JOIN targets t ON p.id=(t.doc->'expected'->>'id')::uuid ORDER BY p.sku;
SELECT 'result|'||jsonb_build_object('updated',{expected},'historyEntries',{expected},'historicalQuotesUnchanged',true,'quotationRows',(SELECT count(*) FROM quotes_before))::text;
COMMIT;
"""
    output=psql(sql)
    suffix='rollback' if rollback else 'after'
    (folder/(suffix+'.jsonl')).write_text('\n'.join(s[6:] for s in output.splitlines() if s.startswith('after|'))+'\n',encoding='utf-8')
    result=[json.loads(s[7:]) for s in output.splitlines() if s.startswith('result|')][0]
    result['snapshotSha256']=hashlib.sha256((folder/(suffix+'.jsonl')).read_bytes()).hexdigest()
    (folder/(suffix+'-result.json')).write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['prepare','apply','rollback']);parser.add_argument('--batch-dir',required=True,type=Path);parser.add_argument('--expected-changes',type=int);a=parser.parse_args()
    if a.mode=='prepare':prepare(a.batch_dir)
    else:apply(a.batch_dir,a.expected_changes,a.mode=='rollback')

#!/usr/bin/env python3
"""One-off missing pasted purchase tax-point repair; prepare/apply/rollback.

Only standard products marked 采购粘贴新增 with absent/null/blank taxPoint
are eligible. Explicit zero and all other sources remain untouched.
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import uuid

spec = importlib.util.spec_from_file_location('freight_helpers', Path(__file__).with_name('repair-legacy-purchase-freight.py'))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
psql, copy_table = helpers.psql, helpers.copy_table

PREDICATE = """payload->>'dataSource'='standard' AND payload->>'sourceSheet'='采购粘贴新增'
AND (payload->>'taxPoint' IS NULL OR btrim(payload->>'taxPoint')='')"""

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def eligible(row):
    p = row['payload']
    value = p.get('taxPoint')
    return p.get('dataSource') == 'standard' and p.get('sourceSheet') == '采购粘贴新增' and (value is None or isinstance(value, str) and not value.strip())

def prepare(args):
    batch = args.batch_dir
    batch.mkdir(parents=True, exist_ok=False, mode=0o700)
    text = psql(args, f"BEGIN READ ONLY; SELECT to_jsonb(p)::text FROM purchase_product p WHERE {PREDICATE} ORDER BY sku; COMMIT;")
    (batch/'before.jsonl').write_text(text, encoding='utf8')
    rows = [json.loads(line) for line in text.splitlines() if line]
    assert all(eligible(row) for row in rows)
    with (batch/'changes.csv').open('w', encoding='utf-8-sig', newline='') as out:
        writer = csv.writer(out)
        writer.writerow(['SKU','原票点','修正票点','来源','原版本'])
        for r in rows:
            writer.writerow([r['sku'],repr(r['payload'].get('taxPoint')),'8%','采购粘贴新增',r['version']])
    manifest = dict(batch=batch.name, database=args.database, container=args.container, count=len(rows),
                    before_sha256=digest(batch/'before.jsonl'), changes_sha256=digest(batch/'changes.csv'),
                    authorization='User requested missing tax points in previously pasted records be filled with 8%; preserve explicit zero and other sources.')
    (batch/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(manifest, ensure_ascii=False))

def transaction_sql(args, manifest, before, after=None):
    rollback = after is not None
    expected = manifest['count']
    batch = manifest['batch']
    action = 'purchase.paste-tax-rollback' if rollback else 'purchase.paste-tax-repair'
    detail = json.dumps(manifest, ensure_ascii=False).replace("'", "''")
    sql = "BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='90s';\n"
    sql += copy_table('before_snapshot', before)
    if rollback:
        sql += copy_table('after_snapshot', after)
    sql += """
CREATE TEMP TABLE targets ON COMMIT DROP AS SELECT (doc->>'id')::uuid id,doc FROM before_snapshot;
ALTER TABLE targets ADD PRIMARY KEY(id);
LOCK TABLE purchase_product IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE quotation_record IN SHARE MODE;
CREATE TEMP TABLE untouched ON COMMIT DROP AS
 SELECT p.id,to_jsonb(p) doc FROM purchase_product p WHERE NOT EXISTS(SELECT 1 FROM targets t WHERE t.id=p.id);
CREATE TEMP TABLE quotes_before ON COMMIT DROP AS SELECT q.id,to_jsonb(q) doc FROM quotation_record q;
"""
    current = 'after_snapshot' if rollback else 'before_snapshot'
    sql += f"""
DO $$ BEGIN
 IF current_database()<>'{args.database}' THEN RAISE EXCEPTION 'Wrong database'; END IF;
 IF (SELECT count(*) FROM targets)<>{expected} THEN RAISE EXCEPTION 'Target count differs'; END IF;
 IF EXISTS(SELECT 1 FROM audit_log WHERE resource_id='{batch}' AND action='{action}')
 THEN RAISE EXCEPTION 'Batch already recorded'; END IF;
 IF EXISTS(SELECT 1 FROM {current} s LEFT JOIN purchase_product p ON p.id=(s.doc->>'id')::uuid
   WHERE to_jsonb(p) IS DISTINCT FROM s.doc) THEN RAISE EXCEPTION 'Snapshot changed; refusing overwrite'; END IF;
 IF EXISTS(SELECT 1 FROM targets WHERE doc->'payload'->>'dataSource' IS DISTINCT FROM 'standard'
   OR doc->'payload'->>'sourceSheet' IS DISTINCT FROM '采购粘贴新增'
   OR coalesce(btrim(doc->'payload'->>'taxPoint'),'')<>'')
 THEN RAISE EXCEPTION 'Target outside pasted missing scope'; END IF;
"""
    if rollback:
        sql += f"""
 IF NOT EXISTS(SELECT 1 FROM audit_log WHERE resource_id='{batch}' AND action='purchase.paste-tax-repair')
 THEN RAISE EXCEPTION 'Missing apply audit'; END IF;
 IF (SELECT count(*) FROM after_snapshot)<>{expected} OR EXISTS(
   SELECT 1 FROM targets t WHERE NOT EXISTS(SELECT 1 FROM after_snapshot a WHERE a.doc->>'id'=t.id::text))
 THEN RAISE EXCEPTION 'Post-repair snapshot incomplete'; END IF;
"""
    sql += "END $$;\n"
    replacement = "t.doc->'payload'" if rollback else "jsonb_set(t.doc->'payload','{taxPoint}','0.08'::jsonb,true)"
    sql += f"""
UPDATE purchase_product p SET payload={replacement},version=p.version+1,updated_at=clock_timestamp()
FROM targets t WHERE p.id=t.id;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM targets t JOIN purchase_product p USING(id) WHERE p.payload IS DISTINCT FROM {replacement}
    OR (to_jsonb(p)-'payload'-'version'-'updated_at') IS DISTINCT FROM (t.doc-'payload'-'version'-'updated_at'))
 THEN RAISE EXCEPTION 'Unexpected target changes'; END IF;
 IF EXISTS(SELECT 1 FROM {current} s JOIN purchase_product p ON p.id=(s.doc->>'id')::uuid
   WHERE p.version<>(s.doc->>'version')::bigint+1 OR p.updated_at<=(s.doc->>'updated_at')::timestamptz)
 THEN RAISE EXCEPTION 'Version or timestamp mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM untouched u FULL JOIN
   (SELECT p.id,to_jsonb(p) doc FROM purchase_product p WHERE NOT EXISTS(SELECT 1 FROM targets t WHERE t.id=p.id)) p USING(id)
   WHERE u.doc IS DISTINCT FROM p.doc) THEN RAISE EXCEPTION 'Unrelated purchase changed'; END IF;
 IF EXISTS(SELECT 1 FROM quotes_before b FULL JOIN
   (SELECT q.id,to_jsonb(q) doc FROM quotation_record q) q USING(id)
   WHERE b.doc IS DISTINCT FROM q.doc) THEN RAISE EXCEPTION 'Historical quotations changed'; END IF;
END $$;
INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
VALUES('{uuid.uuid4()}','{batch}','deploy-maintenance','{action}','purchase_product','{batch}','success','{detail}'::jsonb,clock_timestamp());
SELECT to_jsonb(p)::text FROM purchase_product p JOIN targets t USING(id) ORDER BY p.sku;
COMMIT;
"""
    return sql

def mutate(args):
    batch = args.batch_dir
    manifest = json.loads((batch/'manifest.json').read_text(encoding='utf8'))
    if (manifest['batch'],manifest['database'],manifest['container']) != (batch.name,args.database,args.container):
        raise ValueError('Batch target mismatch')
    if args.expected_changes != manifest['count'] or not args.expected_changes:
        raise ValueError('Reviewed positive --expected-changes required')
    for name, key in [('before.jsonl','before_sha256'),('changes.csv','changes_sha256')]:
        if digest(batch/name) != manifest[key]: raise ValueError('Backup integrity mismatch')
    before = (batch/'before.jsonl').read_text(encoding='utf8').splitlines()
    after = None
    if args.mode == 'rollback':
        receipt = json.loads((batch/'apply-receipt.json').read_text(encoding='utf8'))
        if digest(batch/'after.jsonl') != receipt['sha256']: raise ValueError('After integrity mismatch')
        after = (batch/'after.jsonl').read_text(encoding='utf8').splitlines()
    sql = transaction_sql(args,manifest,before,after)
    (batch/(args.mode+'.sql')).write_text(sql,encoding='utf8')
    output = psql(args,sql)
    result = batch/('rollback-after.jsonl' if after is not None else 'after.jsonl')
    result.write_text(output,encoding='utf8')
    receipt = dict(batch=batch.name,action=args.mode,count=manifest['count'],sha256=digest(result))
    (batch/(args.mode+'-receipt.json')).write_text(json.dumps(receipt,indent=2),encoding='utf8')
    print(json.dumps(receipt))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['prepare','apply','rollback'])
    parser.add_argument('--batch-dir',type=Path,required=True)
    parser.add_argument('--expected-changes',type=int)
    parser.add_argument('--container',default='quotation-prod-quotation-postgres-1')
    parser.add_argument('--database',default='quotation_prod',choices=['quotation_prod','paste_tax_test'])
    parser.add_argument('--db-user',default='quotation_app',choices=['quotation_app','postgres'])
    args=parser.parse_args()
    if not re.fullmatch(r'paste-taxpoint-[A-Za-z0-9-]{1,40}',args.batch_dir.name) or not args.container.startswith('quotation-'):
        parser.error('Use a named quotation container and paste-taxpoint- batch')
    os.umask(0o077)
    prepare(args) if args.mode=='prepare' else mutate(args)

if __name__=='__main__':
    main()

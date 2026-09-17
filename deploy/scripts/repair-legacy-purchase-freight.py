#!/usr/bin/env python3
"""One-off, reviewed legacy freight repair. No application or schema migration.

Run prepare, review the private batch directory, then apply --expected-changes N.
rollback restores only this batch's freight and refuses any subsequent edits.
All database operations use docker/psql; no credentials or dependencies required.
"""
import argparse
import collections
import csv
from decimal import Decimal, ROUND_CEILING
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


def freight(weight):
    return max(Decimal(1), (Decimal(str(weight)) / 50).to_integral_value(rounding=ROUND_CEILING) / 2)


def classification(row, imported_ids=None):
    p = row['payload']
    if p.get('dataSource') != 'legacy_2026':
        return 'standard'
    if imported_ids is not None and row['id'] not in imported_ids:
        return 'untraced'
    if p.get('freeShipping') == '是':
        return 'free'
    w = p.get('weightG')
    if isinstance(w, bool) or not isinstance(w, (int, float)) or w <= 0:
        return 'invalid'
    if w > 10000:
        return 'heavy'
    f = p.get('singleFreightCny')
    if isinstance(f, bool) or not isinstance(f, (int, float)) or Decimal(str(f)) != freight(w):
        return 'changed'
    return 'matching'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def psql(args, sql):
    command = ['docker', 'exec', '-i', args.container, 'psql', '-X', '-qAt',
               '-U', args.db_user, '-d', args.database, '-v', 'ON_ERROR_STOP=1']
    result = subprocess.run(command, input=sql.encode('utf-8'), capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace') +
                           '\nDatabase command failed. Inspect batch audit before retrying any write.')
    return result.stdout.decode('utf-8')


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line]


def report(path, title, rows, changed=False):
    headers = ['SKU', '重量(g)', '原始重量', '原单件采购运费(元)', '修正运费(元)' if changed else '处理',
               '上架状态', '源工作表', '源行']
    out = ['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>' + html.escape(title) +
           '</title><style>body{font:14px system-ui;margin:32px;color:#182638}table{border-collapse:collapse;width:100%}'
           'th,td{padding:8px;border:1px solid #ccd5df;text-align:left}th{background:#eaf0f7;position:sticky;top:0}'
           'tr:nth-child(even){background:#f8fafc}</style><h1>' + html.escape(title) + '</h1>' +
           '<p>来源：报价生产数据库本批次修改前快照。运费为每件人民币金额；重量为克。共 ' + str(len(rows)) + ' 条。</p>' +
           '<table><thead><tr>' + ''.join('<th>' + h + '</th>' for h in headers) + '</tr></thead><tbody>']
    for r in rows:
        p = r['payload']
        values = [r['sku'], p.get('weightG'), p.get('weightOriginal'), p.get('singleFreightCny'),
                  freight(p['weightG']) if changed else ('超过10000g，保留待核对' if classification(r) == 'heavy' else '缺失或无效重量，保留待补全'),
                  r['catalog_state'], p.get('sourceSheet'), p.get('sourceRow')]
        out.append('<tr>' + ''.join('<td>' + html.escape('' if v is None else str(v)) + '</td>' for v in values) + '</tr>')
    path.write_text(''.join(out) + '</tbody></table></html>', encoding='utf-8')


PROVENANCE_SQL = """
SELECT DISTINCT ON(p.id) jsonb_build_object('id',p.id,'job_id',j.id,'source_hash',j.source_hash,
 'source_name',j.source_name,'source_sheet',r.source_sheet,'source_row',r.source_row,
 'applied_version',r.applied_version,'applied_at',r.applied_at) AS doc
FROM purchase_product p JOIN purchase_import_row r ON r.applied_product_id=p.id
JOIN import_job j ON j.id=r.job_id
WHERE p.payload->>'dataSource'='legacy_2026' AND j.payload->>'importProfile'='legacy-2026'
 AND p.source_hash=j.source_hash AND r.applied_at IS NOT NULL
 AND r.rolled_back_at IS NULL AND j.rolled_back_at IS NULL
 AND j.status IN ('completed','completed-with-errors')
 AND coalesce(p.payload->>'sourceSheet','')<>'采购粘贴新增'
ORDER BY p.id,r.applied_at DESC,r.id
"""


def prepare(args):
    batch = args.batch_dir
    batch.mkdir(parents=True, mode=0o700, exist_ok=False)
    output = psql(args, "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT 'row|'||to_jsonb(p)::text FROM purchase_product p ORDER BY id;"
                  + "SELECT 'provenance|'||doc::text FROM (" + PROVENANCE_SQL + ") provenance; COMMIT;")
    snapshot = '\n'.join(line[4:] for line in output.splitlines() if line.startswith('row|')) + '\n'
    provenance = '\n'.join(line[11:] for line in output.splitlines() if line.startswith('provenance|')) + '\n'
    before = batch / 'before.jsonl'
    before.write_text(snapshot, encoding='utf-8')
    (batch / 'provenance.jsonl').write_text(provenance, encoding='utf-8')
    origins = read_rows(batch / 'provenance.jsonl')
    imported_ids = {r['id'] for r in origins}
    rows = read_rows(before)
    counts = dict(collections.Counter(classification(r, imported_ids) for r in rows))
    counts['total'] = len(rows)
    counts['legacy'] = len(rows) - counts.get('standard', 0)
    counts['traced_legacy'] = len(imported_ids)
    report(batch / 'changes.html', '旧采购运费修改前后对照', [r for r in rows if classification(r, imported_ids) == 'changed'], True)
    report(batch / 'heavy.html', '超重异常清单（本批次未修改）', [r for r in rows if classification(r, imported_ids) == 'heavy'])
    report(batch / 'invalid.html', '缺失或无效重量清单（本批次未修改）', [r for r in rows if classification(r, imported_ids) == 'invalid'])
    manifest = {'batch': batch.name, 'database': args.database, 'container': args.container,
                'counts': counts, 'sha256': {p.name: digest(p) for p in batch.iterdir() if p.is_file()},
                'import_job_ids': sorted({r['job_id'] for r in origins}),
                'rule': 'traceable legacy-2026 import batch + legacy_2026; non-free; 0 < weightG <= 10000; max(1,ceil(weightG/50)/2)',
                'actor': 'deploy-maintenance', 'authorization': 'User approved one-time legacy freight repair; preserve free and anomalous weights.'}
    (batch / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def copy_table(name, rows):
    buf = io.StringIO(newline='')
    writer = csv.writer(buf, lineterminator='\n')
    for row in rows:
        writer.writerow([row if isinstance(row,str) else json.dumps(row, ensure_ascii=False, separators=(',', ':'))])
    return f'CREATE TEMP TABLE {name}(doc jsonb) ON COMMIT DROP;\nCOPY {name}(doc) FROM STDIN WITH (FORMAT csv);\n' + buf.getvalue() + '\\.\n'


TARGET_SQL = """
CREATE TEMP TABLE targets ON COMMIT DROP AS
WITH candidate AS (
 SELECT doc, CASE WHEN jsonb_typeof(doc->'payload'->'weightG')='number'
   THEN (doc->'payload'->>'weightG')::numeric END AS w
 FROM before_snapshot
 WHERE doc->'payload'->>'dataSource'='legacy_2026'
   AND coalesce(doc->'payload'->>'freeShipping','')<>'是'
   AND coalesce(doc->'payload'->>'sourceSheet','')<>'采购粘贴新增'
   AND EXISTS(SELECT 1 FROM provenance_snapshot origin WHERE origin.doc->>'id'=before_snapshot.doc->>'id'
     AND origin.doc->>'source_hash'=before_snapshot.doc->>'source_hash')
)
SELECT (doc->>'id')::uuid AS id,doc,greatest(1,ceil(w/50)*0.5) AS fee
FROM candidate WHERE w>0 AND w<=10000
 AND doc->'payload'->'singleFreightCny' IS DISTINCT FROM to_jsonb(greatest(1,ceil(w/50)*0.5));
ALTER TABLE targets ADD PRIMARY KEY(id);
"""


def transaction_sql(args, manifest, before, origins, after=None):
    rollback = after is not None
    batch = manifest['batch']
    action = 'purchase.legacy-freight-rollback' if rollback else 'purchase.legacy-freight-repair'
    expected = manifest['counts'].get('changed', 0)
    # Only validated identifiers and internally generated metadata enter SQL.
    detail = json.dumps({'batch': batch, 'count': expected, 'before_sha256': manifest['sha256']['before.jsonl'],
                         'authorization': manifest['authorization'], 'rule': manifest['rule']}, ensure_ascii=False).replace("'", "''")
    sql = "BEGIN; SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='90s';\n"
    sql += copy_table('before_snapshot', before) + copy_table('provenance_snapshot', origins) + TARGET_SQL
    if rollback:
        sql += copy_table('after_snapshot', after)
    sql += "LOCK TABLE purchase_product IN SHARE ROW EXCLUSIVE MODE;\n"
    # Shared lock for saved quotations blocks edits only during this short transaction;
    # comparing complete rows makes the 'historical quotes unchanged' claim verifiable.
    sql += "LOCK TABLE quotation_record IN SHARE MODE;\nCREATE TEMP TABLE quotes_before ON COMMIT DROP AS SELECT id,to_jsonb(q) AS doc FROM quotation_record q;\n"
    sql += f"""
DO $$ BEGIN
 IF current_database()<>'{args.database}' THEN RAISE EXCEPTION 'Wrong database'; END IF;
 IF (SELECT count(*) FROM targets)<>{expected} THEN RAISE EXCEPTION 'Target count differs from reviewed count'; END IF;
 IF EXISTS(SELECT 1 FROM audit_log WHERE resource_id='{batch}' AND action='{action}')
 THEN RAISE EXCEPTION 'Batch action already recorded; refusing replay'; END IF;
"""
    if rollback:
        sql += f"""
 IF NOT EXISTS(SELECT 1 FROM audit_log WHERE resource_id='{batch}' AND action='purchase.legacy-freight-repair')
 THEN RAISE EXCEPTION 'No committed repair audit'; END IF;
 IF EXISTS(SELECT 1 FROM targets t LEFT JOIN purchase_product p ON p.id=t.id
 LEFT JOIN after_snapshot a ON (a.doc->>'id')::uuid=t.id
 WHERE p.id IS NULL OR a.doc IS NULL OR to_jsonb(p) IS DISTINCT FROM a.doc)
 THEN RAISE EXCEPTION 'Post-repair edits detected; rollback refused'; END IF;
"""
    else:
        sql += """
 IF EXISTS(SELECT 1 FROM provenance_snapshot origin FULL JOIN (""" + PROVENANCE_SQL + """) live
 ON origin.doc->>'id'=live.doc->>'id' WHERE origin.doc IS DISTINCT FROM live.doc)
 THEN RAISE EXCEPTION 'Import provenance changed; prepare and review a fresh batch'; END IF;
"""
        sql += """
 IF EXISTS(SELECT 1 FROM targets t LEFT JOIN purchase_product p ON p.id=t.id
 WHERE p.id IS NULL OR to_jsonb(p) IS DISTINCT FROM t.doc)
 THEN RAISE EXCEPTION 'Snapshot changed; prepare and review a fresh batch'; END IF;
"""
    sql += "END $$;\nCREATE TEMP TABLE current_before ON COMMIT DROP AS SELECT id,to_jsonb(p) AS doc FROM purchase_product p;\n"
    payload = "t.doc->'payload'" if rollback else "jsonb_set(p.payload,'{singleFreightCny}',to_jsonb(t.fee),true)"
    sql += f"""
UPDATE purchase_product p SET payload={payload},version=p.version+1,updated_at=clock_timestamp()
FROM targets t WHERE p.id=t.id;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM purchase_product p JOIN current_before b USING(id) LEFT JOIN targets t USING(id)
 WHERE (t.id IS NULL AND to_jsonb(p) IS DISTINCT FROM b.doc)
 OR (t.id IS NOT NULL AND (
 (to_jsonb(p)-'payload'-'version'-'updated_at') IS DISTINCT FROM (b.doc-'payload'-'version'-'updated_at')
 OR (p.payload-'singleFreightCny') IS DISTINCT FROM ((b.doc->'payload')-'singleFreightCny')
 OR p.version<>(b.doc->>'version')::bigint+1 OR p.updated_at<=(b.doc->>'updated_at')::timestamptz)))
 THEN RAISE EXCEPTION 'Unexpected non-freight field change'; END IF;
 IF (SELECT count(*) FROM purchase_product)<>(SELECT count(*) FROM current_before)
 THEN RAISE EXCEPTION 'Unexpected row count change'; END IF;
 IF EXISTS(SELECT 1 FROM quotation_record q FULL JOIN quotes_before b USING(id)
 WHERE q.id IS NULL OR b.id IS NULL OR to_jsonb(q) IS DISTINCT FROM b.doc)
 THEN RAISE EXCEPTION 'Historical quotations changed'; END IF;
"""
    correct = "t.doc->'payload'" if rollback else "jsonb_set(t.doc->'payload','{singleFreightCny}',to_jsonb(t.fee),true)"
    sql += f"""
 IF EXISTS(SELECT 1 FROM purchase_product p JOIN targets t USING(id) WHERE p.payload IS DISTINCT FROM {correct})
 THEN RAISE EXCEPTION 'Freight verification failed'; END IF;
END $$;
INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
VALUES ('{uuid.uuid4()}','{batch}','deploy-maintenance','{action}','purchase_product','{batch}','success','{detail}'::jsonb,clock_timestamp());
SELECT to_jsonb(p)::text FROM purchase_product p ORDER BY id;
COMMIT;
"""
    return sql


def load_manifest(args):
    manifest = json.loads((args.batch_dir / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['database'] != args.database or manifest['container'] != args.container or manifest['batch'] != args.batch_dir.name:
        raise ValueError('Batch database/container/path mismatch')
    for name, sha in manifest['sha256'].items():
        if digest(args.batch_dir / name) != sha:
            raise ValueError('Backup/report integrity mismatch: ' + name)
    return manifest


def mutate(args):
    manifest = load_manifest(args)
    if args.expected_changes != manifest['counts'].get('changed', 0) or args.expected_changes <= 0:
        raise ValueError('Explicit positive reviewed --expected-changes is required')
    before = [line for line in (args.batch_dir / 'before.jsonl').read_text(encoding='utf-8').splitlines() if line]
    origins = [line for line in (args.batch_dir / 'provenance.jsonl').read_text(encoding='utf-8').splitlines() if line]
    rollback = args.mode == 'rollback'
    after = None
    if rollback:
        receipt = json.loads((args.batch_dir / 'apply-receipt.json').read_text(encoding='utf-8'))
        if digest(args.batch_dir / 'after.jsonl') != receipt['after_sha256']:
            raise ValueError('Post-repair snapshot integrity mismatch')
        after = (args.batch_dir / 'after.jsonl').read_text(encoding='utf-8').splitlines()
    sql = transaction_sql(args, manifest, before, origins, after)
    # Persist the exact operation beside the independent before snapshot BEFORE writing.
    (args.batch_dir / (args.mode + '.sql')).write_text(sql, encoding='utf-8')
    output = psql(args, sql)
    out_path = args.batch_dir / ('rollback-after.jsonl' if rollback else 'after.jsonl')
    out_path.write_text(output, encoding='utf-8')
    receipt = {'batch': manifest['batch'], 'action': args.mode, 'changed': args.expected_changes,
               'after_sha256': digest(out_path), 'transaction_verification': 'all rows, fields, versions and saved quotations passed'}
    (args.batch_dir / (args.mode + '-receipt.json')).write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare', 'apply', 'rollback'])
    parser.add_argument('--batch-dir', type=Path, required=True)
    parser.add_argument('--container', default='quotation-prod-quotation-postgres-1')
    parser.add_argument('--database', default='quotation_prod', choices=['quotation_prod', 'legacy_freight_test'])
    parser.add_argument('--db-user', default='quotation_app', choices=['quotation_app', 'postgres'])
    parser.add_argument('--expected-changes', type=int)
    args = parser.parse_args()
    if not re.fullmatch(r'legacy-freight-[A-Za-z0-9-]{1,40}', args.batch_dir.name):
        parser.error('Batch directory name must start legacy-freight- and contain only letters/digits/hyphens (max 55 chars)')
    if not args.container.startswith('quotation-'):
        parser.error('Only explicitly named quotation containers are allowed')
    os.umask(0o077)
    prepare(args) if args.mode == 'prepare' else mutate(args)


if __name__ == '__main__':
    main()

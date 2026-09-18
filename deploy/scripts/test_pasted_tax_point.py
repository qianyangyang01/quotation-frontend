"""Repair integration tests; disposable local PostgreSQL only."""
import argparse
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

spec = importlib.util.spec_from_file_location('tax_repair', Path(__file__).with_name('repair-pasted-tax-point.py'))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class PastedTaxRepairTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'quotation-tax-test-' + uuid.uuid4().hex[:8]
        subprocess.run(['docker', 'run', '-d', '--name', cls.container, '--label', 'purpose=pasted-tax-test',
                        '--tmpfs', '/var/lib/postgresql/data:rw,size=256m', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                        '-e', 'POSTGRES_DB=paste_tax_test', 'postgres:16.4-alpine'], check=True, capture_output=True)
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'rm', '-f', cls.container], check=True, capture_output=True))
        cls.db = argparse.Namespace(container=cls.container, db_user='postgres', database='paste_tax_test')
        for _ in range(40):
            try:
                repair.psql(cls.db, 'SELECT 1;')
                break
            except RuntimeError:
                time.sleep(.25)
        repair.psql(cls.db, '''CREATE TABLE purchase_product(
            id uuid PRIMARY KEY,sku varchar(96) UNIQUE NOT NULL,payload jsonb NOT NULL,
            version bigint NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
            catalog_state varchar(24) NOT NULL,quote_ready boolean NOT NULL,source_hash varchar(64));
            CREATE TABLE quotation_record(id uuid PRIMARY KEY,payload jsonb NOT NULL);
            CREATE TABLE audit_log(id uuid PRIMARY KEY,request_id varchar(64) NOT NULL,
            actor_account varchar(24) NOT NULL,action varchar(96) NOT NULL,resource_type varchar(64) NOT NULL,
            resource_id varchar(120),outcome varchar(24) NOT NULL,detail jsonb NOT NULL,created_at timestamptz NOT NULL);''')

    def setUp(self):
        repair.psql(self.db, 'TRUNCATE purchase_product,quotation_record,audit_log;')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.args = argparse.Namespace(**vars(self.db), mode='prepare', batch_dir=Path(self.tmp.name)/'paste-taxpoint-test', expected_changes=4)
        rows = []
        for sku,tax in [('ABSENT',None),('NULL',None),('BLANK',''),('SPACE','  '),('ZERO',0),('EIGHT',.08),('THIRTEEN',.13),('LEGACY',None),('OTHER',None)]:
            p = dict(dataSource='legacy_2026' if sku=='LEGACY' else 'standard', sourceSheet='Excel' if sku=='OTHER' else '采购粘贴新增',
                     taxPoint=tax, purchasePriceCny=12.34, weightG=153, singleFreightCny=2, notes="quote' slash\\ newline\n中文")
            if sku=='ABSENT': del p['taxPoint']
            rows.append(dict(id=str(uuid.uuid4()),sku=sku,payload=p,version=7,
                             created_at='2026-01-01T00:00:00+00:00',updated_at='2026-01-02T00:00:00+00:00',
                             catalog_state='disabled' if sku=='BLANK' else 'ready',quote_ready=sku!='BLANK',source_hash='a'*64))
        sql='BEGIN;'+repair.copy_table('fixture',rows)
        sql+='INSERT INTO purchase_product SELECT (jsonb_populate_record(NULL::purchase_product,doc)).* FROM fixture;'
        sql+=f"INSERT INTO quotation_record VALUES('{uuid.uuid4()}','{{\"historicalPrice\":25.55}}');COMMIT;"
        repair.psql(self.db,sql)
        self.quotes=repair.psql(self.db,'SELECT to_jsonb(q) FROM quotation_record q;')
        with contextlib.redirect_stdout(io.StringIO()): repair.prepare(self.args)

    def records(self):
        return {r['sku']:r for r in map(json.loads,repair.psql(self.db,'SELECT to_jsonb(p) FROM purchase_product p;').splitlines())}

    def mutate(self,mode):
        self.args.mode=mode
        with contextlib.redirect_stdout(io.StringIO()): repair.mutate(self.args)

    def test_apply_scope_and_rollback(self):
        original=self.records()
        self.mutate('apply')
        after=self.records()
        for sku,before in original.items():
            if repair.eligible(before):
                expected=dict(before['payload'],taxPoint=.08)
                self.assertEqual(after[sku]['payload'],expected)
                self.assertEqual(after[sku]['version'],8)
                self.assertEqual({k:v for k,v in after[sku].items() if k not in ('payload','version','updated_at')},
                                 {k:v for k,v in before.items() if k not in ('payload','version','updated_at')})
            else: self.assertEqual(after[sku],before)
        self.assertEqual(self.quotes,repair.psql(self.db,'SELECT to_jsonb(q) FROM quotation_record q;'))
        with self.assertRaisesRegex(RuntimeError,'already recorded'): self.mutate('apply')
        self.mutate('rollback')
        for sku,row in self.records().items():
            self.assertEqual(row['payload'],original[sku]['payload'])
            self.assertEqual(row['version'],9 if repair.eligible(original[sku]) else 7)
        self.assertEqual(repair.psql(self.db,'SELECT count(*) FROM audit_log;').strip(),'2')

    def test_concurrent_target_edit_aborts_entire_batch(self):
        repair.psql(self.db,"UPDATE purchase_product SET version=8 WHERE sku='NULL';")
        original=self.records()
        with self.assertRaisesRegex(RuntimeError,'Snapshot changed'): self.mutate('apply')
        self.assertEqual(self.records(),original)
        self.assertEqual(repair.psql(self.db,'SELECT count(*) FROM audit_log;').strip(),'0')

    def test_rollback_preserves_later_changes(self):
        self.mutate('apply')
        repair.psql(self.db,"UPDATE purchase_product SET payload=jsonb_set(payload,'{purchasePriceCny}','33'),version=9 WHERE sku='NULL';")
        original=self.records()
        with self.assertRaisesRegex(RuntimeError,'Snapshot changed'): self.mutate('rollback')
        self.assertEqual(self.records(),original)

    def test_unrelated_edits_survive(self):
        repair.psql(self.db,"UPDATE purchase_product SET version=8 WHERE sku='ZERO';")
        self.mutate('apply')
        self.mutate('rollback')
        self.assertEqual(self.records()['ZERO']['version'],8)

    def test_backup_tampering_rejected(self):
        with (self.args.batch_dir/'before.jsonl').open('a',encoding='utf8') as out: out.write('\n')
        with self.assertRaisesRegex(ValueError,'integrity mismatch'): self.mutate('apply')

    def test_wrong_count_rejected(self):
        self.args.expected_changes=5
        with self.assertRaisesRegex(ValueError,'expected-changes'): self.mutate('apply')


if __name__=='__main__': unittest.main(verbosity=2)

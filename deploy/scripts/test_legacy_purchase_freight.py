"""Integration tests use a disposable local PostgreSQL container, never production."""
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

spec = importlib.util.spec_from_file_location('repair', Path(__file__).with_name('repair-legacy-purchase-freight.py'))
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class FreightRepairTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'quotation-freight-test-' + uuid.uuid4().hex[:8]
        subprocess.run(['docker', 'run', '-d', '--name', cls.container, '--label', 'purpose=legacy-freight-test',
                        '--tmpfs', '/var/lib/postgresql/data:rw,size=256m', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
                        '-e', 'POSTGRES_DB=legacy_freight_test', 'postgres:16.4-alpine'], check=True, capture_output=True)
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'rm', '-f', cls.container], check=True, capture_output=True))
        cls.db = argparse.Namespace(container=cls.container, db_user='postgres', database='legacy_freight_test')
        for _ in range(40):
            try:
                repair.psql(cls.db, 'SELECT 1;')
                break
            except RuntimeError:
                time.sleep(0.25)
        repair.psql(cls.db, '''CREATE TABLE purchase_product(
            id uuid PRIMARY KEY,sku varchar(96) UNIQUE NOT NULL,payload jsonb NOT NULL,
            version bigint NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,
            catalog_state varchar(24) NOT NULL,quote_ready boolean NOT NULL,source_hash varchar(64));
            CREATE TABLE quotation_record(id uuid PRIMARY KEY,payload jsonb NOT NULL);
            CREATE TABLE import_job(id uuid PRIMARY KEY,payload jsonb,source_hash text,source_name text,rolled_back_at timestamptz,status text);
            CREATE TABLE purchase_import_row(id uuid PRIMARY KEY,job_id uuid,applied_product_id uuid,applied_version bigint,
            applied_at timestamptz,rolled_back_at timestamptz,source_sheet text,source_row int);
            CREATE TABLE audit_log(id uuid PRIMARY KEY,request_id varchar(64) NOT NULL,
            actor_account varchar(24) NOT NULL,action varchar(96) NOT NULL,resource_type varchar(64) NOT NULL,
            resource_id varchar(120),outcome varchar(24) NOT NULL,detail jsonb NOT NULL,created_at timestamptz NOT NULL);''')

    def setUp(self):
        repair.psql(self.db, 'TRUNCATE purchase_product,quotation_record,audit_log,import_job,purchase_import_row;')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.args = argparse.Namespace(**vars(self.db), mode='prepare', batch_dir=Path(self.tmp.name) / 'legacy-freight-test', expected_changes=None)
        self.rows = []
        variants = [('W100',100,9),('W101',101,9),('W150',150,9),('W151',151,9),
                    ('W523',523,9),('W569',569,9),('FRACTION',100.01,9),('MAX',10000,9),
                    ('MATCH',550,5.5),('TINY',0.1,0),('ZERO',0,9),('MISSING',None,9),
                    ('HEAVY',480490,9),('FREE',500,0),('STANDARD',500,9),('PENDING',550,None),('NOFIELD',500,None),
                    ('UNTRACED',523,9),('PASTE',523,9),('MISLABEL-PASTE',523,9)]
        for sku,w,f in variants:
            p = {'dataSource':'standard' if sku in ('STANDARD','PASTE') else 'legacy_2026','weightG':w,
                 'purchasePriceCny':28.18,'freeShipping':'是' if sku=='FREE' else '',
                 'singleFreightCny':f,'freight10Cny':12,'freight100Cny':88,'weightOriginal':str(w),
                 'notes':'Quotes " apostrophe \' backslash \\ and\nnewline'}
            if sku=='NOFIELD': del p['singleFreightCny']
            if sku in ('PASTE','MISLABEL-PASTE'): p['sourceSheet']='采购粘贴新增'
            self.rows.append({'id':str(uuid.uuid4()),'sku':sku,'payload':p,'version':7,
                              'created_at':'2026-01-01T00:00:00+00:00','updated_at':'2026-01-02T00:00:00+00:00',
                              'catalog_state':'pending_template' if sku=='PENDING' else 'ready',
                              'quote_ready':sku!='PENDING','source_hash':'a'*64})
        sql='BEGIN;'+repair.copy_table('fixture', self.rows)
        sql+='INSERT INTO purchase_product SELECT (jsonb_populate_record(NULL::purchase_product,doc)).* FROM fixture;'
        job=str(uuid.uuid4())
        sql+=f"INSERT INTO import_job VALUES('{job}','{{\"importProfile\":\"legacy-2026\"}}','{'a'*64}','original.xlsx',NULL,'completed');"
        sql+=f"INSERT INTO purchase_import_row SELECT gen_random_uuid(),'{job}',id,7,'2026-01-02',NULL,'Original',1 FROM purchase_product WHERE sku NOT IN ('UNTRACED','PASTE','STANDARD');"
        sql+=f"INSERT INTO quotation_record VALUES('{uuid.uuid4()}','{{\"oldFreight\":9}}');COMMIT;"
        repair.psql(self.db,sql)
        self.quotes=repair.psql(self.db,'SELECT to_jsonb(q) FROM quotation_record q;')
        with contextlib.redirect_stdout(io.StringIO()): repair.prepare(self.args)
        self.manifest=repair.load_manifest(self.args)
        self.imported_ids={r['id'] for r in repair.read_rows(self.args.batch_dir/'provenance.jsonl')}
        self.args.expected_changes=self.manifest['counts']['changed']

    def run_mutation(self,mode):
        self.args.mode=mode
        with contextlib.redirect_stdout(io.StringIO()): repair.mutate(self.args)

    def records(self):
        return {r['sku']:r for r in map(json.loads,repair.psql(self.db,'SELECT to_jsonb(p) FROM purchase_product p;').splitlines())}

    def test_boundaries(self):
        expected={0.1:1,50:1,50.01:1,99:1,100:1,100.01:1.5,101:1.5,150:1.5,150.01:2,151:2,
                  199:2,200:2,201:2.5,500:5,501:5.5,523:5.5,550:5.5,550.01:6,569:6,10000:100}
        for w,f in expected.items(): self.assertEqual(repair.freight(w),f)

    def test_apply_preserves_scope_and_rollback(self):
        original=self.records()
        self.run_mutation('apply')
        after=self.records()
        for sku,b in original.items():
            a=after[sku]
            if repair.classification(b,self.imported_ids)=='changed':
                self.assertEqual(a['payload']['singleFreightCny'],float(repair.freight(b['payload']['weightG'])))
                self.assertEqual(a['version'],b['version']+1)
                self.assertGreater(a['updated_at'],b['updated_at'])
                self.assertEqual({k:v for k,v in a['payload'].items() if k!='singleFreightCny'},
                                 {k:v for k,v in b['payload'].items() if k!='singleFreightCny'})
                self.assertEqual(a['catalog_state'],b['catalog_state'])
                self.assertEqual(a['quote_ready'],b['quote_ready'])
            else: self.assertEqual(a,b)
        self.assertEqual(self.quotes,repair.psql(self.db,'SELECT to_jsonb(q) FROM quotation_record q;'))
        with self.assertRaisesRegex(RuntimeError,'already recorded'): self.run_mutation('apply')
        self.run_mutation('rollback')
        restored=self.records()
        for sku,b in original.items():
            self.assertEqual(restored[sku]['payload'],b['payload'])
            self.assertEqual(restored[sku]['version'],b['version']+(2 if repair.classification(b,self.imported_ids)=='changed' else 0))
        with self.assertRaisesRegex(RuntimeError,'already recorded'): self.run_mutation('rollback')

    def test_concurrent_change_aborts_entire_batch(self):
        repair.psql(self.db,"UPDATE purchase_product SET version=version+1 WHERE sku='W523';")
        before=self.records()
        with self.assertRaisesRegex(RuntimeError,'Snapshot changed'): self.run_mutation('apply')
        self.assertEqual(self.records(),before)
        self.assertEqual(repair.psql(self.db,'SELECT count(*) FROM audit_log;').strip(),'0')

    def test_rollback_rejects_later_edit(self):
        self.run_mutation('apply')
        repair.psql(self.db,"UPDATE purchase_product SET payload=jsonb_set(payload,'{purchasePriceCny}','33'),version=version+1 WHERE sku='W523';")
        before=self.records()
        with self.assertRaisesRegex(RuntimeError,'Post-repair edits'): self.run_mutation('rollback')
        self.assertEqual(self.records(),before)

    def test_rollback_allows_unrelated_edit(self):
        self.run_mutation('apply')
        repair.psql(self.db,"UPDATE purchase_product SET version=version+1 WHERE sku='STANDARD';")
        self.run_mutation('rollback')
        self.assertEqual(self.records()['STANDARD']['version'],8)

    def test_tampered_backup_refused(self):
        with (self.args.batch_dir/'before.jsonl').open('a',encoding='utf-8') as f: f.write('\n')
        with self.assertRaisesRegex(ValueError,'integrity mismatch'): self.run_mutation('apply')

    def test_wrong_count_refused(self):
        self.args.expected_changes+=1
        with self.assertRaisesRegex(ValueError,'expected-changes'): self.run_mutation('apply')

    def test_import_provenance_changed_aborts(self):
        repair.psql(self.db,"UPDATE import_job SET status='rolled-back';")
        before=self.records()
        with self.assertRaisesRegex(RuntimeError,'Import provenance changed'): self.run_mutation('apply')
        self.assertEqual(self.records(),before)

    def test_new_data_during_preparation_remains_untouched(self):
        repair.psql(self.db,"UPDATE purchase_product SET payload=jsonb_set(payload,'{singleFreightCny}','123'),version=version+1 WHERE sku='PASTE';")
        repair.psql(self.db,"INSERT INTO purchase_product SELECT gen_random_uuid(),'RECENT',payload,version,created_at,updated_at,catalog_state,quote_ready,source_hash FROM purchase_product WHERE sku='STANDARD';")
        current=self.records()
        self.run_mutation('apply')
        after=self.records()
        for sku in ('PASTE','RECENT'): self.assertEqual(after[sku],current[sku])

    def test_only_original_imports_eligible(self):
        self.assertEqual(self.manifest['counts']['untraced'],2)
        self.assertEqual(self.manifest['counts']['standard'],2)
        before=self.records()
        self.run_mutation('apply')
        after=self.records()
        for sku in ('UNTRACED','PASTE','MISLABEL-PASTE','STANDARD'):
            self.assertEqual(before[sku],after[sku])


if __name__=='__main__': unittest.main(verbosity=2)

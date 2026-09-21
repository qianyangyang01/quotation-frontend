"""Emit the exact, guarded transaction AFTER native parser/billing/finance checks.

Run with the reviewed evidence directory. Does not connect to production.
"""
import json
import pathlib
import sys
import uuid

root = pathlib.Path(sys.argv[1])
def read(name):
    return json.loads((root / name).read_text(encoding='utf-8-sig'))
def literal(value):
    text = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    assert '$repair_json$' not in text
    return '$repair_json$' + text + '$repair_json$::jsonb'

before = read('live-before.json'); a = read('a-before.json')
finance = read('finance-prepared.json'); prepared = read('version-prepared.json')
report = read('change-report.json'); checks = read('tax-calculation-evidence.json')
native = read('native-tax-evidence.json')
assert native['passed'] and native['validatedSnapshots'] > 600
payload = prepared['payload']; old = a['version']; channel = a['channel']; version_id = payload['id']
assert checks['newRouteChecks'] > 800 and checks['unchangedRouteChecks'] > 1000
assert len(prepared['evidence']) == 20 and len(payload['rows']) == 8
assert payload['rows'] == [r for r in old['payload']['rows'] if r['countryCode'] == 'US']
assert payload['summary']['removed'] == 56 and len(report['removedAuthorizations']) == 22
assert a['draftCount'] == 0 and payload['basePublishedVersionId'] == old['id']
repair_id = payload['repairId']

sql = r"""\set ON_ERROR_STOP on
begin isolation level serializable;
set local lock_timeout='10s';
set local statement_timeout='60s';
lock table logistics_channel, logistics_version in share row exclusive mode;
select setting_key,version from finance_setting order by setting_key for update;
do $guard$ begin
 if (select paused from logistics_company_state where singleton) then raise exception 'Logistics rebuild active'; end if;
 if exists(select 1 from logistics_import_batch where status in ('queued','processing')) then raise exception 'Logistics import active'; end if;
"""
sql += f" if logistics_active_dataset() <> '{a['datasetId']}'::uuid then raise exception 'Active dataset changed'; end if;\n"
for key, setting in before['settings'].items():
    sql += f" if not exists(select 1 from finance_setting where setting_key='{key}' and version={setting['version']} and payload={literal(setting['payload'])}) then raise exception 'Finance baseline changed: {key}'; end if;\n"
sql += f""" if (select md5(string_agg(id::text||':'||md5(payload::text),'|' order by id)) from logistics_version) <> '{before['pricesHash']}' then raise exception 'Price versions changed'; end if;
 if (select md5(string_agg(id::text||':'||md5(payload::text)||':'||coalesce(current_version_id::text,''),'|' order by id)) from logistics_channel) <> '{before['channelsHash']}' then raise exception 'Channels changed'; end if;
 if not exists(select 1 from logistics_version where id='{old['id']}' and status='published' and payload={literal(old['payload'])}) then raise exception 'A baseline changed'; end if;
 if exists(select 1 from logistics_version where channel_id='{channel['id']}' and status='draft') then raise exception 'A has a pending draft'; end if;
end $guard$;
"""
for key, value in finance.items():
    sql += f"update finance_setting set payload={literal(value)},version=version+1,updated_at=now() where setting_key='{key}' and version={before['settings'][key]['version']};\n"
    detail = {'repairId': repair_id, 'fromVersion': before['settings'][key]['version'], 'toVersion': before['settings'][key]['version']+1,
              'taxChanges': report['taxChanges'] if key == 'tax-settings' else [], 'removedAuthorizations': report['removedAuthorizations'] if key == 'channel-policies' else []}
    sql += f"insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values('{uuid.uuid4()}','{repair_id}','codex-maintenance','finance.update','finance-setting','{key}','success',{literal(detail)},now());\n"
sql += f"""insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at)
 values('{version_id}','{channel['id']}',{payload['versionNumber']},'published','{payload['sourceHash']}',{literal(payload)},now(),now());
update logistics_version set status='superseded',payload=jsonb_set(payload,'{{status}}','"superseded"') where id='{old['id']}' and status='published';
update logistics_channel set current_version_id='{version_id}',version=version+1,updated_at=now(),payload=payload||jsonb_build_object('currentVersionId','{version_id}','updatedAt','{payload['publishedAt']}') where id='{channel['id']}' and current_version_id='{old['id']}';
"""
proof = {'repairId': repair_id, 'note': payload['auditNote'], 'evidence': prepared['evidence'], 'preservedUsRows': 8, 'removedCountries': 22, 'removedRows': 56}
sql += f"insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select '{uuid.uuid4()}',id,rows_fingerprint,'logistics-billing-v7','verified',{literal(proof)},'codex-maintenance' from logistics_version where id='{version_id}';\n"
detail = {'repairId': repair_id, 'beforeVersionId': old['id'], 'afterVersionId': version_id, 'beforeRows': 64, 'afterRows': 8, 'removedCountries': 22, 'note': payload['auditNote']}
sql += f"insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values('{uuid.uuid4()}','{repair_id}','codex-maintenance','logistics.country-scope.correct','logistics-channel','{channel['id']}','success',{literal(detail)},now());\n"
sql += f"""do $verify$ begin
 if not logistics_version_quote_ready('{version_id}') then raise exception 'New version not quote ready'; end if;
 if not exists(select 1 from logistics_channel where id='{channel['id']}' and current_version_id='{version_id}' and version={channel['version']+1}) then raise exception 'Channel pointer mismatch'; end if;
 if (select rows_fingerprint from logistics_version where id='{old['id']}') <> '{old['rows_fingerprint']}' then raise exception 'Historical rows changed'; end if;
 if (select md5(string_agg(id::text||':'||md5(payload::text),'|' order by id)) from quotation_record) <> '{a['quoteHash']}' then raise exception 'Quotation records changed during review; refresh baseline'; end if;
"""
for key, value in finance.items():
    sql += f" if not exists(select 1 from finance_setting where setting_key='{key}' and version={before['settings'][key]['version']+1} and payload={literal(value)}) then raise exception 'Finance update mismatch'; end if;\n"
sql += f"""end $verify$;
commit;
select setting_key,version from finance_setting where setting_key in ('tax-settings','channel-policies');
select id,version_number,status,jsonb_array_length(payload->'rows') rows,logistics_version_quote_ready(id) quote_ready from logistics_version where id='{version_id}';
"""
(root / 'apply-reviewed-correction.sql').write_text(sql, encoding='utf-8')
print(json.dumps({'newVersion': version_id, 'versionNumber': payload['versionNumber'], 'newRouteChecks': checks['newRouteChecks'], 'unchangedRouteChecks': checks['unchangedRouteChecks'], 'sqlBytes': len(sql.encode('utf-8'))}))

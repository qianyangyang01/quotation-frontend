"""Generate a guarded data-only release after exact-payload frontend/Java checks."""
import hashlib
import json
import pathlib
import sys
import uuid

root = pathlib.Path(sys.argv[1])
def read(name):
    return json.loads((root / name).read_text(encoding='utf-8-sig'))
def literal(value):
    text = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    assert '$sunyou_json$' not in text
    return '$sunyou_json$' + text + '$sunyou_json$::jsonb'

before = read('live-before.json')
prepared = read('finance-prepared.json')
report = read('change-report.json')
checks = read('tax-calculation-evidence.json')
native = read('native-tax-evidence.json')
assert checks['preparedSha256'] == native['preparedSha256'] == hashlib.sha256((root / 'finance-prepared.json').read_bytes()).hexdigest()
assert report['remoteScope'] == 'whole-country' and report['ukSurcharge'] == 'not-applicable'
assert report['blockedCountryCodes'] == ['US', 'GB', 'CA', 'AU', 'NZ', 'MX', 'RO']
assert checks['blockedAuthorizationChecks'] >= 189
assert checks['passed'] and native['passed'] and checks['newTaxChecks'] >= 2900
assert checks['unchangedOtherChannelChecks'] > 20000 and checks['authorizationChecks'] == len(report['authorizations'])
assert native['validatedSnapshots'] > 300 and native['rejectedUnavailable'] >= 48
assert set(prepared) == {'tax-settings', 'channel-policies'}
repair = 'sunyou-finance-20260922'
sql = r"""\set ON_ERROR_STOP on
begin isolation level serializable;
set local lock_timeout='5s';
set local statement_timeout='60s';
lock table logistics_channel,logistics_version,logistics_provider,logistics_company_channel,logistics_company_binding in share mode;
select setting_key,version from finance_setting order by setting_key for update;
do $guard$ begin
 if (select paused from logistics_company_state where singleton) then raise exception 'Logistics rebuild active'; end if;
 if exists(select 1 from logistics_import_batch where status in ('queued','processing')) then raise exception 'Logistics import active'; end if;
"""
sql += f" if logistics_active_dataset() <> '{before['datasetId']}'::uuid then raise exception 'Active dataset changed'; end if;\n"
for key, value in before['settings'].items():
    sql += f" if not exists(select 1 from finance_setting where setting_key='{key}' and version={value['version']} and payload={literal(value['payload'])}) then raise exception 'Finance baseline changed: {key}'; end if;\n"
sql += f""" if (select md5(string_agg(id::text||':'||md5(payload::text),'|' order by id)) from logistics_version) <> '{before['pricesHash']}' then raise exception 'Price versions changed'; end if;
 if (select md5(string_agg(id::text||':'||md5(payload::text)||':'||coalesce(current_version_id::text,''),'|' order by id)) from logistics_channel) <> '{before['channelsHash']}' then raise exception 'Channels changed'; end if;
 if (select count(*) from logistics_channel c join logistics_provider p on p.id=c.provider_id where c.dataset_id=logistics_active_dataset() and c.archived_at is null and p.payload->>'name'='顺友') <> 3 then raise exception 'Sunyou channel scope changed'; end if;
"""
for channel in (c for c in before['channels'] if c['provider'] == '顺友'):
    sql += f" if not exists(select 1 from logistics_company_channel where id='{channel['companyId']}' and enabled and payload={literal(channel['company'])}) then raise exception 'Company channel changed'; end if;\n"
sql += 'end $guard$;\n'
for key, value in prepared.items():
    version = before['settings'][key]['version']
    sql += f"update finance_setting set payload={literal(value)},version=version+1,updated_at=now() where setting_key='{key}' and version={version};\n"
    detail = {'repairId': repair, 'fromVersion': version, 'toVersion': version + 1,
              'channelKeys': [c['key'] for c in report['channels']], 'sources': [{'file': s['file'], 'sha256': s['sha256']} for s in report['sources']],
              'remoteScope': report['remoteScope'], 'blockedCountryCodes': report['blockedCountryCodes'], 'newTaxChecks': checks['newTaxChecks'], 'unchangedOtherChannelChecks': checks['unchangedOtherChannelChecks']}
    sql += f"insert into audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at) values('{uuid.uuid4()}','{repair}','codex-maintenance','finance.update','finance-setting','{key}','success',{literal(detail)},now());\n"
sql += 'do $verify$ begin\n'
for key, value in before['settings'].items():
    expected = prepared.get(key, value['payload'])
    version = value['version'] + (1 if key in prepared else 0)
    sql += f" if not exists(select 1 from finance_setting where setting_key='{key}' and version={version} and payload={literal(expected)}) then raise exception 'Finance verification failed: {key}'; end if;\n"
sql += f""" if (select md5(string_agg(id::text||':'||md5(payload::text),'|' order by id)) from logistics_version) <> '{before['pricesHash']}' then raise exception 'Unexpected price write'; end if;
 if (select md5(string_agg(id::text||':'||md5(payload::text)||':'||coalesce(current_version_id::text,''),'|' order by id)) from logistics_channel) <> '{before['channelsHash']}' then raise exception 'Unexpected channel write'; end if;
end $verify$;
commit;
select setting_key,version from finance_setting order by setting_key;
"""
output = root / 'apply-reviewed-sunyou-finance.sql'
output.write_text(sql, encoding='utf-8')
print(json.dumps({'sqlSha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'updatedSettings': list(prepared), 'bytes': output.stat().st_size}))

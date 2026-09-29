"""Read-only preparation of an exact channel/attribute matrix; no database writes.

Usage: prepare-attribute-matrix.py SOURCE SNAPSHOT OUTPUT_DIRECTORY
SOURCE may have a .crdownload suffix; ZIP integrity and cell content are checked.
Missing channels are reported and never replaced with similarly named products.
"""
import copy
import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata
import uuid
import zipfile
import openpyxl


def normalized(value):
    return re.sub(r'\s+', '', unicodedata.normalize('NFKC', value or '')).lower()


def read_matrix(path):
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, 'Incomplete workbook ZIP'
    # WPS may incorrectly declare A1:A1; read actual cells, not the cached dimensions.
    with open(path, 'rb') as stream:
        book = openpyxl.load_workbook(stream, read_only=False, data_only=True)
    sheet = book['Sheet1']
    headers = [c.value for c in sheet[1]]
    assert headers[:2] == ['物流商', '标准渠道名']
    assert headers[2:] == ['普货', '化妆品', '服装', '带电', '纯电', '保健品', '香水', '大货普货', '大货带电', '以色列自提', '以色列到门']
    result = []
    seen = set()
    for number, cells in enumerate(sheet.iter_rows(min_row=2, values_only=True), 2):
        if not any(v is not None for v in cells):
            continue
        provider, name = cells[:2]
        assert provider and name and all(v in (None, '', '√') for v in cells[2:])
        attributes = [headers[c] for c, value in enumerate(cells) if c >= 2 and value == '√']
        correction = ''
        # Explicit user correction in this conversation takes precedence over these two cells.
        if provider == '云途' and name in ('云途大货18000专线挂号（特惠带电）', '云途大货18000专线挂号（特惠普货）'):
            attributes = ['大货带电' if '带电' in name else '大货普货']
            correction = '用户确认第22–23行大货属性勾反，按渠道对应带电/普货修正'
        identity = (normalized(provider), normalized(name))
        assert identity not in seen, f'Duplicate channel: {name}'
        seen.add(identity)
        result.append({'provider': provider, 'name': name, 'attributes': attributes, 'sourceRow': number, 'correction': correction})
    assert len(result) == 112
    return headers[2:], result


def match_channel(item, channels):
    provider = {'急速': '急速国际'}.get(item['provider'], item['provider'])
    name = {('万邦', '万邦大货专线挂号普货'): '万邦大货专线普货',
            ('急速', '美国化妆品专线（商派）'): '急速-美国化妆品专线（商派）'}.get((item['provider'], item['name']), item['name'])
    hits = []
    for channel in channels:
        company = channel.get('company') or {}
        names = [channel['name'], company.get('channelName', ''), *company.get('aliases', [])]
        if normalized(provider) == normalized(channel['provider']) and normalized(name) in {normalized(n) for n in names}:
            hits.append(channel)
    assert len(hits) <= 1, f'Ambiguous channel: {item}'
    return hits[0] if hits else None


def prepare(source, snapshot):
    assert not snapshot['paused'] and snapshot['activeImports'] == 0
    attributes, matrix = read_matrix(source)
    policies = copy.deepcopy(snapshot['settings']['channel-policies']['payload'])
    before = copy.deepcopy(policies)
    categories = {p['category']: p for p in policies}
    assert len(categories) == len(policies)
    country_metadata = {r['country']: r for r in snapshot['settings']['country-classification']['payload']}
    country_by_code = {r['code'].upper(): r for r in country_metadata.values() if r.get('code')}
    matched, missing, unavailable = [], [], []
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for item in matrix:
        channel = match_channel(item, snapshot['channels'])
        if channel is None:
            missing.append(item)
            continue
        if not channel['enabled'] or not channel['quoteReady']:
            unavailable.append({**item, 'reason': '保留既有停用或计费待核验状态，仅配置属性，不强制启用运价'})
        key = f"{channel['ruleId']}::{channel['provider']}::{channel['code']}"
        destinations = [{**r, 'name': country_by_code.get(r['code'].upper(), {}).get('country', r['name'])}
                        for r in channel['countries'] or []]
        countries = sorted({c['name'] for c in destinations if c['name'] and c['code']})
        entry = {**item, 'key': key, 'channelId': channel['id'], 'canonicalName': channel['name'], 'countries': countries}
        matched.append(entry)
        for attribute in attributes:
            if attribute not in categories:
                policy = {'id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'milano:logistics-attribute:' + attribute)), 'category': attribute,
                          'countryRules': [], 'enabled': True, 'updatedAt': stamp}
                categories[attribute] = policy
                policies.append(policy)
            policy = categories[attribute]
            for rule in policy['countryRules']:
                rule['allowedChannels'] = [k for k in rule['allowedChannels'] if k != key]
            if attribute not in item['attributes']:
                continue
            target_countries = countries
            if attribute in ('以色列自提', '以色列到门'):
                target_countries = [c['name'] for c in destinations if c['code'].upper() == 'IL']
                if not target_countries:
                    unavailable.append({**item, 'attribute': attribute, 'reason': '已发布价格未覆盖以色列'})
            policy['enabled'] = True
            for country in sorted(set(target_countries)):
                rule = next((r for r in policy['countryRules'] if r['country'] == country), None)
                if rule is None:
                    meta = country_metadata.get(country)
                    assert meta is not None, f'Missing country metadata: {country}'
                    rule = {k: meta[k] for k in ('country', 'stage', 'continent', 'sortOrder')}
                    rule['allowedChannels'] = []
                    policy['countryRules'].append(rule)
                rule['allowedChannels'].append(key)
    prior = {p['category']: p for p in before}
    for policy in policies:
        if policy != prior.get(policy['category']):
            policy['updatedAt'] = stamp
    changed = []
    for policy in policies:
        old = prior.get(policy['category'], {'countryRules': []})
        old_pairs = {(r['country'], k) for r in old['countryRules'] for k in r['allowedChannels']}
        new_pairs = {(r['country'], k) for r in policy['countryRules'] for k in r['allowedChannels']}
        changed.append({'attribute': policy['category'], 'added': sorted(new_pairs-old_pairs), 'removed': sorted(old_pairs-new_pairs)})
    report = {'source': str(source), 'sha256': hashlib.sha256(Path(source).read_bytes()).hexdigest(), 'rows': len(matrix),
              'matched': matched, 'missing': missing, 'unavailable': unavailable, 'changes': changed,
              'scope': 'Only listed channels and listed attributes; Israel attributes only for IL; other channels/settings retained.'}
    return policies, report


if __name__ == '__main__':
    source, snapshot, output = map(Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    payload, report = prepare(source, json.loads(snapshot.read_text(encoding='utf-8-sig')))
    (output/'attribute-policies.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    (output/'attribute-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'rows': report['rows'], 'matched': len(report['matched']), 'missing': report['missing'],
                      'unavailable': report['unavailable'], 'changes': [{**c, 'added': len(c['added']), 'removed': len(c['removed'])} for c in report['changes']]}, ensure_ascii=False))

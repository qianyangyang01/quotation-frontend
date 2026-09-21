"""Build a reviewable, one-time finance correction from a fresh production snapshot.

No network or database writes. Run with SNAPSHOT OUTPUT_DIRECTORY. Existing rules
are copied exactly; source channel IDs refer to the approved active dataset.
"""
import copy
import datetime
import json
import pathlib
import sys

DONORS = {628: 586, 634: 563, 635: 577, 636: 596, 637: 595, 638: 599,
          639: 588, 640: 589, 641: 588, 642: 589}
INCLUDED = {629, 630, 631, 632, 633}
A_COMPANY = '0c9f62ef-f7d8-48ea-b79b-fca3672fd52e'


def key(channel):
    return f"{channel['ruleId']}::{channel['provider']}::{channel['code']}"


def prepare(snapshot):
    channels = {c['ruleId']: c for c in snapshot['channels']}
    assert channels[632]['companyId'] == A_COMPANY
    assert channels[632]['name'] == '巧捷小包全球特惠A(服装)'
    assert channels[631]['name'] == '巧捷专线小包全球特惠F(敏感)'
    assert all(channels[n]['provider'] == channels[d]['provider'] for n, d in DONORS.items())
    assert all(channels[n]['enabled'] and channels[n]['quoteReady'] for n in DONORS.keys() | INCLUDED)
    countries = {c['code']: c['country'] for c in snapshot['settings']['country-classification']['payload']}
    resource = pathlib.Path(__file__).resolve().parents[2] / 'backend/src/main/resources/eu-member-states.json'
    eu_codes = {r[0] for r in json.loads(resource.read_text(encoding='utf-8'))}
    before = snapshot['settings']['tax-settings']['payload']
    original_countries = {c['country']: c for c in before['countries']}
    after = copy.deepcopy(before)
    by_name = {c['country']: c for c in after['countries']}
    assert by_name['欧盟']['selected']
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    changes = []

    def upsert(country, field, rule, donor=None):
        rules = country.setdefault(field, [])
        matches = [r for r in rules if r['key'] == rule['key']]
        assert len(matches) <= 1
        if matches:
            assert matches[0] == rule, f"Unexpected existing rule: {country['country']} {rule['key']}"
            return
        rules.append(rule)
        changes.append({'country': country['country'], 'field': field, 'rule': rule, 'donor': donor})

    for target in sorted(DONORS.keys() | INCLUDED):
        channel = channels[target]
        destinations = {'US'} if target == 632 else {c['code'] for c in channel['countries']}
        groups = {'欧盟' if c in eu_codes else countries[c] for c in destinations}
        for group in sorted(groups):
            country = by_name.get(group)
            if target in INCLUDED:
                # AU/GB previously had no duty. Only add this channel's exemption;
                # fixedFeeUsd=0 preserves every unrelated route's effective tax.
                assert country is not None, f'Missing country metadata: {group}'
                if not country['selected']:
                    assert country['fixedFeeUsd'] == 0 and not country.get('channelRules')
                    country['selected'] = True
                    country['enabled'] = True
                upsert(country, 'channelRules', {'key': key(channel), 'mode': 'exempt',
                       'amount': 0, 'currency': 'USD', 'perKg': 0,
                       'source': '用户确认：巧捷4个渠道及急速美国渠道全部包税（2026-09-21）'})
            elif country and original_countries[group]['selected']:
                donor_key = key(channels[DONORS[target]])
                original = next((r for r in country.get('channelRules', []) if r['key'] == donor_key), None)
                assert original is not None, f'Missing donor rule: {group} {donor_key}'
                assert original['mode'] != 'unavailable', f'Donor unavailable on supported route: {group} {target}'
                replacement = copy.deepcopy(original)
                replacement['key'] = key(channel)
                upsert(country, 'channelRules', replacement, donor_key)
        if 'RO' in destinations:
            country = by_name['罗马尼亚']
            assert country['euTaxMode'] == 'add-handling'
            if target in INCLUDED:
                handling = {'key': key(channel), 'mode': 'exempt', 'amount': 0, 'currency': 'CNY', 'perKg': 0}
            else:
                donor_key = key(channels[DONORS[target]])
                handling = copy.deepcopy(next(r for r in country['handlingRules'] if r['key'] == donor_key))
                handling['key'] = key(channel)
                assert handling['currency'] == 'CNY' and handling['amount'] == (14 if target == 628 else 41)
            upsert(country, 'handlingRules', handling, None if target in INCLUDED else donor_key)

    # Preserve global providers and all original channel rules, including CHC/E-packet.
    assert after['providers'] == before['providers']
    for old in before['countries']:
        new = by_name[old['country']]
        for field in ('channelRules', 'handlingRules'):
            assert new.get(field, [])[:len(old.get(field, []))] == old.get(field, [])
        assert new.get('euTaxMode') == old.get('euTaxMode')
    after['updatedAt'] = stamp
    policies = copy.deepcopy(snapshot['settings']['channel-policies']['payload'])
    removed = []
    a_key = key(channels[632])
    for policy in policies:
        changed = False
        for country in policy['countryRules']:
            if country['country'] == countries['US']:
                continue
            for field in ('allowedChannels', 'unavailableChannels'):
                if a_key in country.get(field, []):
                    country[field].remove(a_key)
                    removed.append({'attribute': policy['category'], 'country': country['country'], 'field': field})
                    changed = True
        if changed:
            policy['updatedAt'] = stamp
    report = {'channels': [{'ruleId': n, 'key': key(channels[n]), 'name': channels[n]['name'],
                           'donorKey': key(channels[DONORS[n]]) if n in DONORS else None,
                           'countries': ['US'] if n == 632 else sorted(c['code'] for c in channels[n]['countries'])}
                          for n in sorted(DONORS.keys() | INCLUDED)],
              'taxChanges': changes, 'removedAuthorizations': removed,
              'fromVersions': {k: snapshot['settings'][k]['version'] for k in ('tax-settings', 'channel-policies')}}
    return {'tax-settings': after, 'channel-policies': policies}, report


if __name__ == '__main__':
    snapshot = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
    output = pathlib.Path(sys.argv[2]); output.mkdir(parents=True, exist_ok=True)
    payloads, report = prepare(snapshot)
    for name, value in [('finance-prepared', payloads), ('change-report', report)]:
        (output / f'{name}.json').write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'channels': len(report['channels']), 'taxRules': sum(c['field'] == 'channelRules' for c in report['taxChanges']),
                      'handlingRules': sum(c['field'] == 'handlingRules' for c in report['taxChanges']),
                      'removedAuthorizations': len(report['removedAuthorizations']), 'fromVersions': report['fromVersions']}, ensure_ascii=False))

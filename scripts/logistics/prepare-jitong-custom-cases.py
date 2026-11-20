"""Independent tariff arithmetic from the original workbook extraction, not parsed prices."""
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import json
import sys

folder = Path(sys.argv[1])
expected = json.loads((folder / 'expected.json').read_text(encoding='utf-8'))
D = lambda n: Decimal(str(n))
cases = []
for channel in expected['channels']:
    samples, rejected = [], []
    for country in channel['countries']:
        rows = [r for r in channel['rows'] if r['countryCode'] == country['code']]
        upper = max(D(r['weightToKg']) for r in rows)
        weights = {D('.000001'), D('.001'), D('.049999'), D('.05'), D('.050001')}
        for row in rows:
            lo, hi = D(row['weightFromKg']), D(row['weightToKg'])
            weights.update({hi, (lo + hi) / 2, hi - D('.000001')})
            if lo > 0:
                weights.update({lo, lo + D('.000001')})
            if hi < upper:
                weights.add(hi + D('.000001'))
        for weight in sorted(weights):
            charge = max(weight, D('.05'))
            hits = [r for r in rows if (charge > D(r['weightFromKg']) or charge == D(r['weightFromKg']) and r['weightFromInclusive']) and (charge < D(r['weightToKg']) or charge == D(r['weightToKg']) and r['weightToInclusive'])]
            assert len(hits) == 1, (channel['name'], country, weight, hits)
            row = hits[0]
            total = (charge * D(row['pricePerKg']) + D(row['registrationFee'])).quantize(D('.01'), rounding=ROUND_HALF_UP)
            samples.append(dict(country=country['code'], weightKg=float(weight), chargeWeightKg=float(charge), total=float(total), sourceRow=row['sourceRow']))
        rejected.extend([dict(country=country['code'], weightKg=0), dict(country=country['code'], weightKg=float(upper + D('.000001')))])
    rejected.extend(dict(country=c, weightKg=.1) for c in ['US', 'GB', 'CO', 'JP', 'XX'])
    cases.append(dict(name=channel['name'], samples=samples, rejected=rejected))
(folder / 'billing-cases.json').write_text(json.dumps(dict(sourceSha256=expected['sha256'], channels=cases), ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(channels=len(cases), samples=sum(len(c['samples']) for c in cases), rejections=sum(len(c['rejected']) for c in cases))))

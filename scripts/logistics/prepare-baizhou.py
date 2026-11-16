from pathlib import Path
import json, shutil
from decimal import Decimal, ROUND_CEILING
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'artifacts/baizhou-bz503-20261116'
P=ROOT.parents[1]/'release-packages/quotation-2026.11.16-01'
P.mkdir(parents=True,exist_ok=True)
e=json.loads((A/'source-evidence.json').read_text(encoding='utf-8'))
cells={k:v for row in e['rows'] for k,v in row.items()}
countries={'美国':'US','英国':'GB','德国':'DE','法国':'FR','意大利':'IT','西班牙':'ES','葡萄牙':'PT'}
rows=[]; country=None; previous={}; eta=None
for r in range(4,11):
    country=cells.get(f'A{r}',country)
    eta=cells.get(f'F{r}',eta) # F4:F13 explicitly merges US and GB.
    lo,hi=[Decimal(v) for v in str(cells[f'C{r}']).upper().replace('KG','').split('-')]
    code=countries[country]; prior=previous.get(code)
    assert lo==0 if prior is None else lo-prior==Decimal('.001')
    previous[code]=hi
    times=str(eta).split('-'); rate=cells[f'D{r}'];fee=cells[f'E{r}']
    assert isinstance(rate,(int,float)) and rate>0 and isinstance(fee,(int,float)) and fee>=0
    rows.append(dict(sourceSheet=e['sheet'],sourceRow=r,countryCode=code,areaName=country,weightFromKg=float(lo),weightToKg=float(hi),weightFromInclusive=prior is not None,weightToInclusive=True,pricePerKg=rate,registrationFee=fee,etaMinDays=int(times[0]),etaMaxDays=int(times[-1]),minChargeWeightKg=.001,pricingModel='per-kg-1g-ceil-cny'))
assert len(rows)==7 and set(previous)=={'US'}
expected=dict(source=e['source'],sha256=e['sha256'],rows=rows)
cases=[]
for r in rows:
    lo=Decimal(str(r['weightFromKg']));hi=Decimal(str(r['weightToKg']))
    for weight in sorted({max(lo,Decimal('.000001')),hi,(lo+hi)/2,max(lo-Decimal('.000001'),Decimal('.000001'))}):
        charge=max(weight,Decimal('.001')).quantize(Decimal('.001'),rounding=ROUND_CEILING)
        matches=[x for x in rows if x['countryCode']==r['countryCode'] and Decimal(str(x['weightFromKg']))<=charge<=Decimal(str(x['weightToKg']))]
        assert len(matches)==1
        x=matches[0];total=(charge*Decimal(str(x['pricePerKg']))+Decimal(str(x['registrationFee']))).quantize(Decimal('1'),rounding=ROUND_CEILING)
        cases.append(dict(country=r['countryCode'],weightKg=float(weight),total=float(total),sourceRow=x['sourceRow']))
rejected=[dict(country=c,weightKg=float(hi+Decimal('.000001'))) for c,hi in previous.items()]+[dict(country='US',weightKg=0)]+[dict(country=c,weightKg=.5) for c in ['CA','GB','DE','FR','IT','ES','PT']]
for name,data in [('expected.json',expected),('billing-cases.json',dict(samples=cases,rejected=rejected))]:
    (P/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copyfile(ROOT/'scripts/logistics/baizhou-company-channel.json',P/'company.json')
shutil.copyfile(Path('C:/Users/25490/Desktop')/e['source'],P/'source.xlsx')
print(json.dumps(dict(rows=len(rows),countries=1,priceFields=14,boundaryCases=len(cases),rejectedCases=len(rejected),package=str(P)),ensure_ascii=False))

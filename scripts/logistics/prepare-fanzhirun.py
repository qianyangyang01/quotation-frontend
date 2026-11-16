from pathlib import Path
from decimal import Decimal, ROUND_CEILING
import sys, json, hashlib, shutil
ROOT=Path(__file__).resolve().parents[2]
A=ROOT/'artifacts/fanzhirun-us-20261116'
sys.path.insert(0,str(A/'python-libs'))
import xlrd
P=ROOT.parents[1]/'release-packages/fanzhirun-us-20261116'
P.mkdir(parents=True,exist_ok=True)
source=Path('C:/Users/25490/Desktop/帆之润-美国小包VIP价格表7.14(2).xls')
w=xlrd.open_workbook(source,formatting_info=True);s=w.sheet_by_name('美国小包-普货')
e=dict(source=source.name,sha256=hashlib.sha256(source.read_bytes()).hexdigest(),sheet=s.name,sheets=w.sheet_names(),rows=[s.row_values(r) for r in range(s.nrows)],merges=s.merged_cells)
(A/'source-evidence.json').write_text(json.dumps(e,ensure_ascii=False,indent=2),encoding='utf-8')
rows=[]
for r in range(3,8):
    lo,hi=map(Decimal,s.cell_value(r,3).split('-'))
    rate=s.cell_value(r,4);fee=s.cell_value(r,5)
    assert s.cell_type(r,4)==xlrd.XL_CELL_NUMBER and s.cell_type(r,5)==xlrd.XL_CELL_NUMBER and rate>0 and fee>=0
    rows.append(dict(sourceSheet=s.name,sourceRow=r+1,countryCode='US',areaName='美国',weightFromKg=float(lo),weightToKg=float(hi),weightFromInclusive=True,weightToInclusive=True,pricePerKg=rate,registrationFee=fee,etaMinDays=7,etaMaxDays=15,minChargeWeightKg=.05,volumeDivisor=6000,pricingModel='per-kg-1g-ceil-cny',sourcePriceCell=f'E{r+1}',sourceFeeCell=f'F{r+1}'))
assert [(x['pricePerKg'],x['registrationFee']) for x in rows]==[(62,17),(62,17),(76,16),(76,12),(76,12)]
samples=[]
weights={Decimal('.000001'),Decimal('.049999'),Decimal('.05'),Decimal('.050001')}
for r in rows:
    lo=Decimal(str(r['weightFromKg']));hi=Decimal(str(r['weightToKg']))
    weights.update([lo,hi,(lo+hi)/2,max(lo-Decimal('.000001'),Decimal('.000001'))])
    if hi<3:weights.add(hi+Decimal('.000001'))
for weight in sorted(weights):
    charge=max(weight,Decimal('.05')).quantize(Decimal('.001'),rounding=ROUND_CEILING)
    hits=[r for r in rows if Decimal(str(r['weightFromKg']))<=charge<=Decimal(str(r['weightToKg']))];assert len(hits)==1
    r=hits[0];total=(charge*Decimal(str(r['pricePerKg']))+Decimal(str(r['registrationFee']))).quantize(Decimal('1'),rounding=ROUND_CEILING)
    samples.append(dict(country='US',weightKg=float(weight),total=int(total),sourceRow=r['sourceRow']))
for name,data in [('expected',dict(source=source.name,sha256=e['sha256'],rows=rows)),('billing-cases',dict(samples=samples,rejected=[dict(country='US',weightKg=3.000001),dict(country='US',weightKg=0),dict(country='CA',weightKg=.1)]))]:
    (P/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copyfile(source,P/'source.xls');shutil.copyfile(ROOT/'scripts/logistics/fanzhirun-company-channel.json',P/'company.json')
print(json.dumps(dict(package=str(P),rows=len(rows),priceFields=10,boundaries=len(samples)),ensure_ascii=False))

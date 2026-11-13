from pathlib import Path
import json, shutil, re
from decimal import Decimal, ROUND_HALF_UP
ROOT=Path(__file__).resolve().parents[2]; A=ROOT/'artifacts/rongding-cosmetics-20261113'
P=ROOT.parents[1]/'release-packages/rongding-cosmetics-20261113'; P.mkdir(parents=True,exist_ok=True)
e=json.loads((A/'source-evidence.json').read_text(encoding='utf-8'))
d=json.loads((A/'directory-version.json').read_text(encoding='utf-8'))
cells={k:v for row in e['rows'] for k,v in row.items()}
entries=[x for x in d['directory'] if x['id']=='7242c2e4-ac63-3bbf-8b12-f9ef5cb869f3']
assert len(entries)==1
new=dict(id='f1789e9c-769a-4f87-a751-42453252f1e1',providerName='容鼎',channelName='美国标准DDP专线-特惠化妆品A(SP)',enabled=True,logisticsAttribute=entries[0]['logisticsAttribute'],productCodes=['PK00369'],aliases=[],providerAliases=[],sharedSourceCodes=[],sources=[dict(file=e['source'],sheet=e['sheet'],firstRow=28,lastRow=33,priceRows=6)])
entries.append(new)
scope=dict(enabled=True,revision=d['state']['revision'],entries=entries)
channels=[]
for entry,start,code in zip(entries,[22,28],['PK00270','PK00369']):
    rows=[]
    for i in range(6):
        r=start+i; lo,hi=map(Decimal,cells[f'D{r}'].split('-'))
        rows.append(dict(sourceRow=r,sourceSheet=e['sheet'],countryCode='US',weightFromKg=float(lo),weightToKg=float(hi),weightFromInclusive=i!=1,weightToInclusive=True,minChargeWeightKg=.05,pricePerKg=cells[f'E{r}'],registrationFee=cells[f'F{r}'],etaMinDays=8,etaMaxDays=12))
    channels.append(dict(companyChannelId=entry['id'],channelName=entry['channelName'],code=code,rows=rows))
for exp,old in zip(channels[0]['rows'],d['current']['payload']['rows']):
    for k,v in exp.items():assert old[k]==v,(k,v,old[k])
assert d['current']['payload']['quoteReady'] and d['current']['payload']['pricingReady']
expected=dict(source=e['source'],sha256=e['sha256'],channels=channels,existingVersionId=d['current']['id'])
cases=[]
for channel in channels:
    samples=[]
    for row in channel['rows']:
        lo=int(Decimal(str(row['weightFromKg']))*1000)+(0 if row['weightFromInclusive'] else 1); hi=int(Decimal(str(row['weightToKg']))*1000)
        for grams in sorted(set([1,49,50,lo,(lo+hi)//2,hi])):
            if grams<50 and row['sourceRow'] not in [22,28]:continue
            if grams>=50 and not lo<=grams<=hi:continue
            weight=Decimal(grams)/1000;charge=max(weight,Decimal('.05'))
            total=(charge*Decimal(str(row['pricePerKg']))+Decimal(str(row['registrationFee']))).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
            samples.append(dict(weightKg=float(weight),total=float(total),sourceRow=row['sourceRow']))
    cases.append(dict(companyChannelId=channel['companyChannelId'],samples=samples,rejected=[dict(country='US',weightKg=5.001),dict(country='US',weightKg=0),dict(country='CA',weightKg=.5),dict(country='US',weightKg=.2005)]))
for name,value in [('expected.json',expected),('company.json',scope),('billing-cases.json',cases)]:
    (P/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    if name=='company.json':(ROOT/'scripts/logistics/rongding-cosmetics-company-channels.json').write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copyfile(Path('C:/Users/25490/Desktop')/e['source'],P/'source.xlsx')
print(json.dumps(dict(package=str(P),existingAlreadyMatches=True,newChannel=new['channelName'],sampleCount=sum(len(c['samples'])+len(c['rejected']) for c in cases)),ensure_ascii=False))

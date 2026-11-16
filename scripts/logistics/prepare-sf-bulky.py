from pathlib import Path
import json, shutil, zipfile, subprocess, sys
from decimal import Decimal, ROUND_CEILING, ROUND_HALF_UP
sys.stdout.reconfigure(encoding='utf-8')
W=Path(__file__).resolve().parents[2]
P=W.parents[1]/'release-packages/sf-europe-bulky-20261116'
P.mkdir(parents=True,exist_ok=True)
e=json.loads((W/'artifacts/sf-europe-bulky-20261116/source-evidence.json').read_text(encoding='utf-8'));c=e['cells']
b=json.loads((W/'artifacts/sf-europe-bulky-20261116/baseline.json').read_text(encoding='utf-8'))
company=dict(enabled=True,revision=b['state']['revision'],entries=[dict(id='10bd9c26-404d-4f4f-bb12-dd86b832f968',providerName='顺丰',providerAliases=[],channelName='欧洲-泡货专线',productCodes=[],aliases=[],sharedSourceCodes=[],logisticsAttribute='普货',enabled=True,sources=[dict(file=e['source'],sheet=e['sheet'],firstRow=4,lastRow=55,priceRows=52)])])
rows=[]
for n in range(4,56):
    if n%2==0: code=c[f'E{n}']; country=c[f'D{n}']
    lo,hi=[float(x) for x in c[f'I{n}'].replace('KG','').split('-')]
    rows.append(dict(sourceRow=n,sourceSheet=e['sheet'],countryCode=code,areaName=country,weightFromKg=lo,weightToKg=hi,weightFromInclusive=n%2==1 and code!='GB',weightToInclusive=True,pricePerKg=c[f'G{n}'],registrationFee=c[f'H{n}'],etaMinDays=12,etaMaxDays=20,billingStepKg=.001,volumeDivisor=18000,minChargeWeightKg=0))
assert len(rows)==52 and len({r['countryCode'] for r in rows})==26
for name,obj in [('expected.json',dict(source=e['source'],sha256=e['sha256'],companyChannelId=company['entries'][0]['id'],rows=rows)),('company.json',company)]:
    (P/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
shutil.copyfile(Path('C:/Users/25490/Desktop')/e['source'],P/'source.xlsx')
samples=[]; rejected=[]
for code in sorted({r['countryCode'] for r in rows}):
    cr=[r for r in rows if r['countryCode']==code];limit=Decimal(str(cr[-1]['weightToKg']));boundary=Decimal(str(cr[0]['weightToKg']))
    for w in sorted({Decimal('.000001'),Decimal('.001'),Decimal('.123456'),Decimal('1'),boundary-Decimal('.000001'),boundary,boundary+Decimal('.000001'),boundary+Decimal('.001'),limit-Decimal('.000001'),limit}):
        charge=w.quantize(Decimal('.001'),rounding=ROUND_CEILING)
        r=cr[0] if charge<=boundary else cr[1]
        total=(charge*Decimal(str(r['pricePerKg']))+Decimal(str(r['registrationFee']))).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
        samples.append(dict(country=code,weightKg=float(w),chargeWeightKg=float(charge),total=float(total),sourceRow=r['sourceRow']))
    rejected.extend([dict(country=code,weightKg=0),dict(country=code,weightKg=float(limit+Decimal('.000001')))])
rejected.extend(dict(country=code,weightKg=1) for code in ['US','CY','MT','NO','CH'])
(P/'billing-cases.json').write_text(json.dumps(dict(samples=samples,rejected=rejected),ensure_ascii=False,indent=2),encoding='utf-8')
app=W.parents[1]/'release-packages/quotation-2026.11.16-03-purchase/app.jar'
with zipfile.ZipFile(app) as z:
    for n in z.namelist():
        if n.startswith('BOOT-INF/'):z.extract(n,P/'compile-app')
cp=str(W/'backend/target/classes')+';'+str(P/'compile-app/BOOT-INF/lib/*')
java=Path('C:/Users/25490/.cache/codex-runtimes/temurin21/jdk-21.0.11+10/bin')
subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(P/'runtime'),str(W/'scripts/logistics/ReviewSfBulky.java')],check=True)
subprocess.run([str(java/'java.exe'),'-Xmx640m','-cp',str(P/'runtime')+';'+cp,'com.milano.quotation.logistics.ReviewSfBulky',str(P)],check=True)

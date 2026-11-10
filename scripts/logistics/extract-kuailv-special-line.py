"""Read original cells independently of the application parser; never edit the workbook."""
from pathlib import Path
from decimal import Decimal, ROUND_CEILING
import hashlib,json,zipfile,xml.etree.ElementTree as E
W=Path(__file__).resolve().parents[2];P=W/'artifacts/kuailv-us-special-line-20261110'
source=P/'source.xlsx';ns={'x':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
with zipfile.ZipFile(source) as z:
    strings=[''.join(i.itertext()) for i in E.fromstring(z.read('xl/sharedStrings.xml')).findall('x:si',ns)]
    rel={i.attrib['Id']:i.attrib['Target'] for i in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sheet=next(s for s in E.fromstring(z.read('xl/workbook.xml')).findall('x:sheets/x:sheet',ns) if s.attrib['name']=='美国混合派')
    target=rel[sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']]
    cells={}
    for c in E.fromstring(z.read('xl/'+target)).findall('x:sheetData/x:row/x:c',ns):
        v=c.find('x:v',ns)
        if v is not None and v.text:cells[c.attrib['r']]=strings[int(v.text)] if c.attrib.get('t')=='s' else v.text
assert cells['B21']=='美国专线-特敏感' and cells['G21']=='10-18天'
assert cells['D2']=='重量段（KG）' and cells['E2']=='运费（元/kg)' and cells['F2']=='处理费（元/票）'
rows=[]
for r in range(21,27):
    lower,upper=map(Decimal,cells[f'D{r}'].split('-'))
    rows.append(dict(sourceRow=r,sourceSheet=sheet.attrib['name'],weight=cells[f'D{r}'],weightFromKg=float(lower),weightToKg=float(upper),
                     pricePerKg=int(cells[f'E{r}']),registrationFee=int(cells[f'F{r}']),countryCode='US',country='美国',eta='10-18'))
expected=dict(source='快驴欧洲小包-包税VIP.xlsx',sha256=hashlib.sha256(source.read_bytes()).hexdigest(),channels=[dict(name='美国专线-特敏感',attribute='保健品',rows=rows)],
              confirmedRule='2026-11-10 用户确认：重量按1g向上进位，运费再向上取整到元',sourceBilling=cells['C31'],sourceAttribute=cells['H21'])
(P/'expected.json').write_text(json.dumps(expected,ensure_ascii=False,indent=2),encoding='utf-8')
cases=[]
for r in rows:
    lo=Decimal(str(r['weightFromKg']));hi=Decimal(str(r['weightToKg']))
    for weight in sorted(set([max(lo,Decimal('.000001')),max(lo,Decimal('.001')),max(lo,Decimal('.001'))+Decimal('.000001'),(lo+hi)/2,hi-Decimal('.000001'),hi])):
        charge=max(weight,Decimal('.001')).quantize(Decimal('.001'),rounding=ROUND_CEILING)
        total=(charge*r['pricePerKg']+r['registrationFee']).quantize(Decimal('1'),rounding=ROUND_CEILING)
        cases.append(dict(country='US',weightKg=float(weight),chargeWeightKg=float(charge),total=int(total),sourceRow=r['sourceRow']))
(P/'billing-cases.json').write_text(json.dumps(dict(cases=cases,rejected=[dict(country='US',weightKg=5.000001),dict(country='US',weightKg=0),dict(country='GB',weightKg=.1)]),indent=2),encoding='utf-8')
print(json.dumps(dict(rows=len(rows),priceCells=12,cases=len(cases),sha256=expected['sha256'])))

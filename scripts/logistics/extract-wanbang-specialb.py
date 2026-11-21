"""Independent OOXML source audit, without invoking the application parser."""
import sys,json,zipfile,hashlib,re
from pathlib import Path
from decimal import Decimal,ROUND_HALF_UP
from xml.etree import ElementTree as E
source=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
n={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
countries={'德国':'DE','法国':'FR','意大利':'IT','西班牙':'ES','葡萄牙':'PT','荷兰':'NL','比利时':'BE'}
company='480ea529-69ee-47d1-90a6-12ec8e3c2f0a'
with zipfile.ZipFile(source) as z:
    strings=[''.join(s.itertext()) for s in E.fromstring(z.read('xl/sharedStrings.xml'))]
    book=E.fromstring(z.read('xl/workbook.xml'))
    sheet=next(s for s in book.find('m:sheets',n) if s.get('name')=='万邦优选特敏B')
    rels={r.get('Id'):r.get('Target') for r in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    target=rels[sheet.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')]
    root=E.fromstring(z.read('xl/'+target))
    cells={}
    for c in root.findall('m:sheetData/m:row/m:c',n):
        v=c.find('m:v',n)
        if v is not None:cells[c.get('r')]=strings[int(v.text)] if c.get('t')=='s' else v.text
    for merge in root.findall('m:mergeCells/m:mergeCell',n):
        start,end=merge.get('ref').split(':');col=re.match('[A-Z]+',start)[0]
        if re.match('[A-Z]+',end)[0]!=col:continue
        for r in range(int(re.search('[0-9]+',start)[0]),int(re.search('[0-9]+',end)[0])+1):cells.setdefault(col+str(r),cells.get(start,''))
    rows=[];excluded=[]
    for r in range(6,44):
        v=lambda col:cells.get(col+str(r),'')
        if v('D')!='EUSLHC-P':excluded.append(r);continue
        assert v('C')=='欧洲优选专线特敏B' and v('B') in countries
        lo,hi=[float(x) for x in re.findall(r'[0-9.]+',v('H'))]
        eta=[int(x) for x in re.findall(r'[0-9]+',v('E'))]
        rows.append(dict(country=v('B'),countryCode=countries[v('B')],areaName=v('B'),product=v('C'),code=v('D'),sourceProductCode=v('D'),eta=v('E'),limit=v('F'),size=v('G'),weight=v('H'),
                         sourceSheet='万邦优选特敏B',sourceRow=r,sourceWeightRange=v('H'),weightFromKg=lo,weightToKg=hi,weightFromInclusive=lo>0,weightToInclusive=True,
                         pricePerKg=float(v('I')),registrationFee=float(v('J')),etaMinDays=eta[0],etaMaxDays=eta[-1],minChargeWeightKg=0,currency='CNY',pricingModel='per-kg',
                         sourcePriceCell='I'+str(r),sourceFeeCell='J'+str(r)))
    assert len(rows)==33 and excluded==list(range(16,21))
    ch=dict(channelName='万邦优选特敏B',name='万邦优选特敏B',companyChannelId=company,title=cells['D1'],taxNote=cells['B54'],sheet='万邦优选特敏B',productCode='EUSLHC-P',attributes=['保健品'],
            countries=[{'name':name,'code':code} for name,code in countries.items()],rows=rows)
    result=dict(source=source.name,sourcePath=str(source),sha256=hashlib.sha256(source.read_bytes()).hexdigest(),channels=[ch],excludedClsmRows=excluded)
    (out/'expected.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    samples=[];rejected=[dict(country='US',weightKg=1),dict(country='DE',weightKg=0)]
    for code in countries.values():
        bands=[r for r in rows if r['countryCode']==code]
        for row in bands:
            low=Decimal(str(row['weightFromKg']));high=Decimal(str(row['weightToKg']))
            for w in sorted({low if low else Decimal('.001'),(low+high)/2,high}):
                total=(w*Decimal(str(row['pricePerKg']))+Decimal(str(row['registrationFee']))).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
                samples.append(dict(country=code,weightKg=float(w),chargeWeightKg=float(w),total=float(total),sourceRow=row['sourceRow']))
            if low>0:rejected.append(dict(country=code,weightKg=float(low-Decimal('.0005'))))
        rejected.append(dict(country=code,weightKg=float(Decimal(str(bands[-1]['weightToKg']))+Decimal('.001'))))
    (out/'billing-cases.json').write_text(json.dumps({'channels':[dict(channelName=ch['channelName'],samples=samples,rejected=rejected)]},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'rows':len(rows),'countries':len(countries),'priceFields':len(rows)*2,'samples':len(samples),'rejected':len(rejected)}))

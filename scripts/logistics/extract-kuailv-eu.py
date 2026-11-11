"""Independent, read-only source-cell audit. Never rewrite the carrier workbook."""
from pathlib import Path
from decimal import Decimal, ROUND_CEILING
import hashlib, json, re, zipfile, xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'artifacts/kuailv-eu-special-20261111'
SOURCE = Path('C:/Users/25490/Desktop/快驴欧洲小包-包税VIP.xlsx')
EU = dict(zip('德国 波兰 奥地利 法国 爱尔兰 卢森堡 匈牙利 希腊 罗马尼亚 爱沙尼亚 立陶宛 保加利亚 拉脱维亚 荷兰 意大利 捷克共和国 斯洛伐克 比利时 西班牙 葡萄牙 芬兰 瑞典 丹麦 克罗地亚 斯洛文尼亚 塞浦路斯 马耳他'.split(), 'DE PL AT FR IE LU HU GR RO EE LT BG LV NL IT CZ SK BE ES PT FI SE DK HR SI CY MT'.split()))
NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
with zipfile.ZipFile(SOURCE) as z:
    strings = [''.join(t.text or '' for t in x.findall('.//s:t', NS)) for x in E.fromstring(z.read('xl/sharedStrings.xml'))]
    rels = {x.attrib['Id']: x.attrib['Target'] for x in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sheet = next(s for s in E.fromstring(z.read('xl/workbook.xml')).find('s:sheets', NS) if s.attrib['name'] == '欧洲小包-特货（免税）')
    target = rels[sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']]
    xml = E.fromstring(z.read(target.lstrip('/') if target.startswith('/') else 'xl/' + target))
    cells = {}
    for c in xml.findall('s:sheetData/s:row/s:c', NS):
        v = c.find('s:v', NS)
        value = v.text if v is not None else None
        if c.attrib.get('t') == 's' and value is not None: value = strings[int(value)]
        if c.attrib.get('t') == 'inlineStr': value = ''.join(t.text or '' for t in c.findall('.//s:t', NS))
        if value is not None: cells[c.attrib['r']] = value
    merges = [m.attrib['ref'] for m in xml.findall('s:mergeCells/s:mergeCell', NS)]
    def value(address):
        if address in cells: return cells[address]
        for merge in merges:
            lo, hi = merge.split(':')
            if lo[0] == hi[0] == address[0] and int(lo[1:]) <= int(address[1:]) <= int(hi[1:]): return cells.get(lo, '')
        return ''
    assert [cells[x] for x in ['A2', 'B2', 'C2', 'D2']] == ['国家', '重量', '单价（RMB/KG）', '挂号费(RMB/票)']
    rows, excluded = [], []
    minima, ends = {}, {}
    for r in range(3, 86):
        country = value(f'A{r}')
        item = dict(sourceRow=r, sourceCountry=country, weight=cells[f'B{r}'], pricePerKg=float(Decimal(cells[f'C{r}'])), registrationFee=float(Decimal(cells[f'D{r}'])))
        if country not in EU:
            assert country in ['英国', '挪威', '瑞士', '塞尔维亚'], country
            excluded.append(item)
            continue
        item['countryCode'] = EU[country]
        numbers=re.findall(r'[0-9]+(?:\.[0-9]+)?',item['weight'])
        assert len(numbers) in [2,3], item
        lo,hi=map(Decimal,numbers[:2])
        if len(numbers)==3: minima[country]=Decimal(numbers[2])
        assert country in minima
        item.update(weightFromKg=float(lo),weightToKg=float(hi),minChargeWeightKg=float(minima[country]),weightFromInclusive=country not in ends or lo!=ends[country],weightToInclusive=True)
        ends[country]=hi
        rows.append(item)
    assert set(r['countryCode'] for r in rows) == set(EU.values())
    evidence = dict(file=str(SOURCE), sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(), sheet=sheet.attrib['name'], rows=rows, excluded=excluded, cells=cells, merges=merges,
                    userConfirmed='2026-11-11：时效未提供则留空；马耳他按价格行20kg；罗马尼亚没有手续费，只按价格行；重量1g向上进位，运费合计再向上取整到元')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'source-evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    cases=[]
    for r in rows:
        lo,hi,minimum=(Decimal(str(r[k])) for k in ['weightFromKg','weightToKg','minChargeWeightKg'])
        start=max(lo if r['weightFromInclusive'] else lo+Decimal('.001'),minimum)
        for charged in sorted(set([start,hi,((start+hi)/2).quantize(Decimal('.001'),rounding=ROUND_CEILING)])):
            for actual in [charged,charged-Decimal('.0005')]:
                if actual<=0: continue
                total=(charged*Decimal(str(r['pricePerKg']))+Decimal(str(r['registrationFee']))).quantize(Decimal('1'),rounding=ROUND_CEILING)
                cases.append(dict(country=r['countryCode'],weightKg=float(actual),chargeWeightKg=float(charged),total=int(total),sourceRow=r['sourceRow']))
    rejected=[dict(country=EU[c],weightKg=float(w+Decimal('.000001'))) for c,w in ends.items()]
    rejected += [dict(country=c,weightKg=.5) for c in ['GB','NO','CH','RS','US']]+[dict(country='DE',weightKg=0)]
    (OUT/'billing-cases.json').write_text(json.dumps(dict(cases=cases,rejected=rejected),ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(countries=len(EU), rows=len(rows), priceCells=len(rows)*2, excludedCountries=sorted(set(x['sourceCountry'] for x in excluded)), sha256=evidence['sha256']), ensure_ascii=False))

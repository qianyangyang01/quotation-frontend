"""Read-only extraction of independent original workbook expectations."""
import pathlib, json, hashlib, openpyxl
root = pathlib.Path(__file__).resolve().parents[2]
source = pathlib.Path('C:/Users/25490/Desktop/万邦速达优选专线报价表-20261109-VIP.xlsx')
book = openpyxl.load_workbook(source, data_only=False)
sheet = book['万邦优选服装专线']
def value(row, col):
    cell = sheet.cell(row, col)
    for merged in sheet.merged_cells.ranges:
        if cell.coordinate in merged:
            return sheet.cell(merged.min_row, merged.min_col).value
    return cell.value
codes = dict(zip(['德国','法国','意大利','西班牙','葡萄牙','荷兰','波兰','奥地利','捷克','斯洛伐克','匈牙利','克罗地亚','斯洛文尼亚','罗马尼亚'], ['DE','FR','IT','ES','PT','NL','PL','AT','CZ','SK','HU','HR','SI','RO']))
rows = []
for r in range(6,50):
    country,product,code,eta,limit,size,weight,rate,fee = [value(r,c) for c in range(2,11)]
    assert code == 'EUSLPHFZ-P' and isinstance(rate,(float,int)) and isinstance(fee,(float,int))
    rows.append(dict(sourceRow=r,country=country,countryCode=codes[country],product=product,code=code,eta=eta.replace('--','-'),limit=limit,size=size,weight=weight,pricePerKg=rate,registrationFee=fee))
result = dict(source=source.name,sha256=hashlib.sha256(source.read_bytes()).hexdigest(),weightDecision='2026-11-09 用户确认：忽略重量限制列，以重量段列为准',channels=[dict(channelName=sheet.title,title=sheet['D1'].value,taxNote=sheet['B60'].value,rows=rows)])
target = root/'backend/src/test/resources/wanbang-clothing-20261109.json'
target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(rows=len(rows),countries=len(codes),sha256=result['sha256'])))

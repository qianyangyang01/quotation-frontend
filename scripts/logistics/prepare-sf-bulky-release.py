from pathlib import Path
import subprocess,shutil,json,hashlib,tarfile,zipfile
W=Path(__file__).resolve().parents[2];PACK=W.parents[1]/'release-packages'
V='quotation-2026.11.16-07-sf-bulky';P=PACK/V;P.mkdir(exist_ok=True)
D=PACK/'sf-europe-bulky-20261116';OLD=PACK/'quotation-2026.11.16-01'
SHA=subprocess.check_output(['git','rev-parse','HEAD'],cwd=W,text=True).strip()
s=(OLD/'deploy.py').read_text(encoding='utf-8')
s=s.replace("VERSION='quotation-2026.11.16-01';SHA='7bd97a3699f7c7b306d821dedc37ffe70e0e6236'",f"VERSION='{V}';SHA='{SHA}'")
s=s.replace("OLD=ROOT/'releases/quotation-2026.11.14-02'","OLD=ROOT/'releases/quotation-2026.11.16-06'")
s=s.replace("manifest['git_sha']=='77bb479a1da44a695afae104fe51582b334ce822'","manifest['git_sha']=='393eb0abb19173108491fe4445ee915a0e32df05'")
s=s.replace("manifest['backend_git_sha']=='77bb479a1da44a695afae104fe51582b334ce822'","manifest['backend_git_sha']=='393eb0abb19173108491fe4445ee915a0e32df05'")
s=s.replace('baizhou-bz503-clothing-backend-parser-only','sf-europe-bulky-backend-parser-only').replace('baizhou-bz503-2026.11.16-v20','sf-europe-bulky-2026.11.16-v22')
s=s.replace("len(c['rows'])==7","len(c['rows'])==52")
s=s.replace("c['providerName']=='百州' and c['logisticsAttribute']=='服装' and c['companyChannelId']=='fe84a955-c584-4ed5-9b0e-1c79957c1c83'","c['providerName']=='顺丰' and c['logisticsAttribute']=='普货' and c['companyChannelId']=='10bd9c26-404d-4f4f-bb12-dd86b832f968'")
s=s.replace("assert {r['countryCode'] for r in c['rows']}=={'US'}","assert {r['countryCode'] for r in c['rows']}=={r['countryCode'] for r in expected['rows']}")
s=s.replace("'countries':1,'rows':7,'priceFields':14","'countries':26,'rows':52,'priceFields':104")
s=s.replace(" switched=False"," assert json.loads((AUDIT/'candidate.json').read_text())==candidate\n run(['sha256sum','-c','SHA256SUMS'],'pre-switch-hashes',cwd=AUDIT)\n assert run(['docker','image','inspect','--format','{{index .Config.Labels \"com.milano.quotation.git-sha\"}}','quotation-backend:'+VERSION],'pre-switch-image-sha')==SHA\n switched=False")
(P/'deploy.py').write_text(s,encoding='utf-8')
(P/'Dockerfile.backend').write_text(f'FROM quotation-backend:quotation-2026.11.16-06\nLABEL com.milano.quotation.release="{V}" com.milano.quotation.git-sha="{SHA}"\nCOPY --chown=quotation:quotation app.jar /app/app.jar\n',encoding='utf-8')
(P/'.dockerignore').write_text('*\n!app.jar\n',encoding='utf-8')
for n in ['company.json','source.xlsx','expected.json','billing-cases.json','finance-baseline.json','existing-company.json','validation.json','expected-excluded-channels.json']:
 shutil.copyfile(D/n,P/n)
shutil.copytree(D/'runtime',P/'runtime',dirs_exist_ok=True)
for n in ['RunSfBulky.java','ReviewSfBulky.java']:shutil.copyfile(W/'scripts/logistics'/n,P/n)
with zipfile.ZipFile(W/'backend/target/quotation-backend-0.1.0.jar') as new,zipfile.ZipFile(PACK/'quotation-2026.11.16-06/app.jar') as live:
 resources=lambda z:{n:z.read(n) for n in z.namelist() if n.startswith(('BOOT-INF/classes/db/migration/','BOOT-INF/classes/logistics-repairs/','BOOT-INF/classes/logistics-rules/')) and not n.endswith('/')}
 nr,lr=resources(new),resources(live);assert nr.keys()==lr.keys(),'Immutable resource inventory changed'
 normalized=[]
 for n in nr:
  if nr[n]!=lr[n]:
   assert n.endswith('.sql') and nr[n].replace(b'\r\n',b'\n')==lr[n].replace(b'\r\n',b'\n'),'Immutable resource content changed: '+n
   normalized.append(n)
 with zipfile.ZipFile(P/'app.jar','w') as out:
  for entry in new.infolist():out.writestr(entry,lr[entry.filename] if entry.filename in lr else new.read(entry.filename))
 (P/'resource-line-ending-normalization.json').write_text(json.dumps(normalized,indent=2))
with zipfile.ZipFile(P/'app.jar') as new,zipfile.ZipFile(PACK/'quotation-2026.11.16-06/app.jar') as live:
 assert resources(new)==resources(live),'Immutable packaged resources differ'
h=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
candidate=dict(release=V,sha=SHA,scope='sf-europe-bulky-backend-parser-only',app_sha256=h(P/'app.jar'))
(P/'candidate.json').write_text(json.dumps(candidate,indent=2))
subprocess.run(['git','bundle','create',str(P/'source.bundle'),'HEAD'],cwd=W,check=True)
s=(OLD/'run-maintenance.py').read_text(encoding='utf-8').replace('quotation-2026.11.16-01',V).replace('RunBaizhou','RunSfBulky').replace('BAIZHOU_','SF_BULKY_').replace('f0f4756933af92c3b401e74f6e27b30d932b8faaedd7f4c33c876946d628cf91',candidate['app_sha256'])
(P/'run-maintenance.py').write_text(s,encoding='utf-8')
s=(OLD/'publish-reviewed.py').read_text(encoding='utf-8').replace('quotation-2026.11.16-01',V).replace("len(delta['logistics_provider']['added'])==1","len(delta['logistics_provider']['added'])==0").replace('fe84a955-c584-4ed5-9b0e-1c79957c1c83','10bd9c26-404d-4f4f-bb12-dd86b832f968')
s=s.replace("print(json.dumps(dict(passed=True", "summary=dict(passed=True").replace("backup=str(backup))))", "backup=str(backup))\n(P/'preservation-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))\nprint(json.dumps(summary))")
(P/'publish-reviewed.py').write_text(s,encoding='utf-8')
for script in ['deploy.py','run-maintenance.py','publish-reviewed.py']:compile((P/script).read_text(encoding='utf-8'),script,'exec')
files=['app.jar','source.bundle','deploy.py','Dockerfile.backend','.dockerignore','candidate.json','company.json','source.xlsx','expected.json','billing-cases.json','finance-baseline.json','existing-company.json','validation.json','expected-excluded-channels.json','run-maintenance.py','publish-reviewed.py','RunSfBulky.java','ReviewSfBulky.java']+[p.relative_to(P).as_posix() for p in (P/'runtime').rglob('*') if p.is_file()]
(P/'SHA256SUMS').write_text(''.join(h(P/f)+'  '+f+'\n' for f in files))
with tarfile.open(P/'release-upload.tgz','w:gz') as tar:
 for f in files+['SHA256SUMS']:tar.add(P/f,arcname=f)
print(json.dumps(candidate))

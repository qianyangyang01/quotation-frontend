"""Package the reviewed single-channel scope and resume after import, never replay it."""
from pathlib import Path
import shutil, hashlib, tarfile
W=Path(__file__).resolve().parents[2]
PACK=W.parents[1]/'release-packages'
P=PACK/'quotation-2026.11.16-07-sf-bulky'
D=PACK/'sf-europe-bulky-20261116'
shutil.copyfile(P/'publication-coverage.json',P/'expected-excluded-channels.json')
shutil.copyfile(P/'expected-excluded-channels.json',D/'expected-excluded-channels.json')
shutil.copytree(D/'runtime',P/'runtime',dirs_exist_ok=True)
shutil.copyfile(W/'scripts/logistics/RunSfBulky.java',P/'RunSfBulky.java')
s=(P/'publish-reviewed.py').read_text()
s=s.replace("assert not (P/'import-result.json').exists(),'Do not replay import; inspect existing result'", "assert (P/'import-result.json').exists() and not (P/'final-verification.json').exists(),'Resume only imported unpublished channel'")
s=s.replace("before=snapshot('publication-before')", """before=json.loads((P/'publication-before.json').read_text())
current=snapshot('publication-resume-before')
for t in before:
    assert all(current[t].get(k)==v for k,v in before[t].items()),'Existing state changed before resume: '+t
for t in ['quotation_record','quotation_review']:
    before[t]=current[t]  # Preserve legitimate concurrent additions as well as original snapshots.
for t in ['logistics_provider','finance_setting']:
    assert current[t]==before[t],'Unexpected addition before resume: '+t
assert len(current['logistics_channel'])==len(before['logistics_channel'])+1
assert len(current['logistics_version'])==len(before['logistics_version'])+1
assert len(current['logistics_company_channel'])==len(before['logistics_company_channel'])+1""")
s=s.replace("for action in ['import','publish','verify']:","for action in ['publish','verify']:")
(P/'resume-reviewed.py').write_text(s)
compile(s,'resume-reviewed.py','exec')
files=[line.split('  ',1)[1] for line in (P/'SHA256SUMS').read_text().splitlines()]
files+=['expected-excluded-channels.json','resume-reviewed.py']
files=list(dict.fromkeys(files))
(P/'SHA256SUMS').write_text(''.join(hashlib.sha256((P/f).read_bytes()).hexdigest()+'  '+f+'\n' for f in files))
refresh=['RunSfBulky.java','expected-excluded-channels.json','resume-reviewed.py','SHA256SUMS']+[x.relative_to(P).as_posix() for x in (P/'runtime').rglob('*') if x.is_file()]
with tarfile.open(P/'scope-maintenance-refresh.tgz','w:gz') as tar:
    for f in refresh:tar.add(P/f,arcname=f)

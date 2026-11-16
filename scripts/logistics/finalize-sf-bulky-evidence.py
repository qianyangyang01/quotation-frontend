"""Record final staged artifact provenance without changing application or database state."""
from pathlib import Path
import fcntl, hashlib, json, os
ROOT=Path('/srv/ahmln-data/quotation-app')
VERSION='quotation-2026.11.16-07-sf-bulky'
P=ROOT/'maintenance'/VERSION
os.umask(0o077)
locks=[]
for path in [ROOT/'maintenance/release.lock',Path('/tmp/quotation-production-release.lock')]:
    f=path.open('a');fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB);locks.append(f)
assert (ROOT/'current').resolve().name==VERSION
assert json.loads((P/'preservation-summary.json').read_text())['passed']
manifest=ROOT/'current/manifest.txt'
before=manifest.read_text()
values=dict(line.split('=',1) for line in before.splitlines() if '=' in line)
assert values['backend_git_sha']=='dfca76e1294aac0ffab2feecc6befd91b42dd111'
hashes={name:hashlib.sha256((P/name).read_bytes()).hexdigest() for name in ['release-upload.tgz','maintenance-refresh.tgz','scope-maintenance-refresh.tgz','source.bundle','SHA256SUMS']}
values.update(upload_bundle_sha256=hashes['release-upload.tgz'],maintenance_refresh_sha256=hashes['maintenance-refresh.tgz'],scope_maintenance_refresh_sha256=hashes['scope-maintenance-refresh.tgz'],staged_source_bundle_sha256=hashes['source.bundle'],staging_checksums_sha256=hashes['SHA256SUMS'])
(P/'manifest-before-provenance.txt').write_text(before)
manifest.write_text(''.join(k+'='+v+'\n' for k,v in values.items()))
(P/'manifest-final.txt').write_text(manifest.read_text())
(P/'artifact-provenance.json').write_text(json.dumps(hashes,indent=2))
print('SF_BULKY_PROVENANCE_OK')

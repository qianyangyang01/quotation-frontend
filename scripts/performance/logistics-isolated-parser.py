"""Exercise the internal parser with real files; never writes business data."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.parse
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--corpus', required=True)
p.add_argument('--endpoint', default='http://127.0.0.1:18090')
p.add_argument('--output', required=True)
p.add_argument('--runs', type=int, default=3)
a = p.parse_args()
files = sorted(f for f in pathlib.Path(a.corpus).iterdir() if f.suffix.lower() in ('.xls', '.xlsx'))
report = {'files': len(files), 'runs': [], 'consistent': True}
expected = {}
output = pathlib.Path(a.output)
output.parent.mkdir(parents=True, exist_ok=True)
for run in range(a.runs):
    results = []
    for f in files:
        raw = f.read_bytes()
        request = urllib.request.Request(a.endpoint + '/parse', raw, {
            'Content-Type': 'application/octet-stream',
            'X-Workbook-Name': urllib.parse.quote(f.name),
        })
        start = time.monotonic()
        with urllib.request.urlopen(request, timeout=130) as response:
            result = json.load(response)
        elapsed = round((time.monotonic() - start) * 1000)
        signatures = sorted((c.get('providerName'), c.get('channelName'), c.get('contentHash')) for c in result['channels'])
        if f.name in expected and expected[f.name] != signatures:
            raise AssertionError('Non-deterministic prices: ' + f.name)
        expected[f.name] = signatures
        item = {'file': f.name, 'sha256': hashlib.sha256(raw).hexdigest(), 'ms': elapsed,
                'sheets': len(result['sheets']), 'channels': len(result['channels']),
                'priceRows': sum(len(c['rows']) for c in result['channels']),
                'filteredSheets': [s['name'] for s in result['sheets'] if s.get('filteredPriceRows', 0) > 500],
                'pendingSheets': [s['name'] for s in result['sheets'] if s.get('templateStatus') == 'adapter-required']}
        results.append(item)
        print(json.dumps({'run': run + 1, 'file': f.name, 'ms': elapsed}, ensure_ascii=False), flush=True)
    report['runs'].append({'totalMs': sum(i['ms'] for i in results), 'files': results})
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
assert all(r['totalMs'] <= 120000 for r in report['runs']), 'Batch parse exceeded 120 seconds'

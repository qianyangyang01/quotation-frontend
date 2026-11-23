import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
const release = JSON.parse(readFileSync(new URL('../src/data/applicationRelease.json', import.meta.url), 'utf8'))
assert.match(release.version, /^[a-zA-Z0-9.-]{1,100}$/)
assert.match(release.publishedOn, /^\d{4}-\d{2}-\d{2}$/)
assert.match(release.requiredEpoch, /^[a-zA-Z0-9-]{1,100}$/)
assert.ok(['optional', 'required'].includes(release.policy))
assert.ok(typeof release.title === 'string' && release.title.trim() && release.title.length <= 100)
assert.ok(Array.isArray(release.features) && release.features.length > 0 && release.features.length <= 20)
for (const feature of release.features) for (const [key, max] of [['title', 100], ['description', 600], ['location', 180]]) assert.ok(typeof feature[key] === 'string' && feature[key].trim() && feature[key].length <= max, key)
if (process.argv[2]) {
  const previous = JSON.parse(readFileSync(process.argv[2], 'utf8')).release
  if (previous) {
    assert.notEqual(release.version, previous.version, 'A publication requires a unique release version')
    if (release.policy === 'required') assert.notEqual(release.requiredEpoch, previous.requiredEpoch, 'Critical releases must advance requiredEpoch')
    else assert.equal(release.requiredEpoch, previous.requiredEpoch, 'Optional releases must retain the critical boundary')
  } else assert.equal(release.policy, 'optional', 'Legacy clients require a compatible optional bootstrap; no silent critical migration')
}
console.log('Application release notes and update policy verified')

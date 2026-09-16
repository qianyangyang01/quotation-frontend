# Rare-country channel loading

## Reproduction on quotation-2026.09.16-09

Authenticated production draft QY2601686, template mode; no channels added, no formal quotation saved.
Country-name filtering for UAE completed in 274 ms. First channel loading for Netherlands took 6271 ms and Spain 7205 ms, measured from browser action through disappearance of the loading message, including automation overhead.
Netherlands server access log: upstream/request 0.270 s, response body 828931 bytes. The request included nine countries, not only Netherlands. Server time is not full browser latency.

## Causes and bounded change

- `ensureCountries` requested the union of all loaded/requested countries for each new country, replacing all rules.
- `matchedLogistics` called the single-channel finance check for every relation. Each allowed relation independently rescanned and sorted the entire country's available channel list.
- Country summaries also attempted channel calculation for unloaded countries.

Load only missing countries within a freshly verified publication revision. On revision changes, replace the full country snapshot. Merge using stable channel/version IDs, never display partial/stale results, and retain cancellation and existing save guards. Finance authorization is calculated once per country calculation with no cross-request authorization cache. No pricing formulas, database schemas or production business data are changed.

## Regression requirements

- Same authorization result including disabled/duplicate policies, missing coverage and country aliases.
- Synthetic 60-channel regression: full price scans 3600 -> 60 for identical results (operation count, not an HTTP benchmark).
- Existing country prices and channel selections retained; empty new countries considered loaded.
- Changed revisions replace old rules; stale/mismatched revisions and late cancelled responses rejected.
- Authenticated post-release UI timing must use the exact deployed release and compare first-time countries separately from warm cache.

The reported Spain selected-row blank state is not yet independently reproduced. Do not claim it fixed merely because channel loading completes.

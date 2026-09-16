# Logistics loading performance

Candidate is based on production a9d6e97106278667216b4c54d1bbfac647c2cc0e.

- Refresh finance settings and the logistics manifest concurrently; pass this load's manifest to the rules query. Keep forced finance refresh, cancellation, stale-cache status and save checks.
- Replace per-channel logistics_company_quote_allowed() calls in the read service with equivalent scalar state subqueries and a set of enabled company bindings. Keep revision checks, billing eligibility, dataset and channel/provider status filters. No schema or data migration.
- Do not carry forward the earlier experimental removal of forced finance refresh or cross-request sharing of revision reads: those weaken freshness/transaction isolation.

Production read-only comparison before release, 2026-09-16:

| Version fingerprint SQL | Five warm execution times (ms) |
| --- | --- |
| Original | 19.535, 18.435, 18.827, 18.435, 18.335 |
| Set-based scope | 1.759, 1.525, 1.499, 1.472, 1.425 |

Both directions of EXCEPT ALL found zero result differences. Shared buffer hits fell from 1003 to 259. These are PostgreSQL execution times, not browser or HTTP P95 measurements.

Regression coverage includes unbound channels, disabled bindings, unrestricted company mode, pause, cached-rule invalidation, billing readiness, and stale/verified manifest propagation.

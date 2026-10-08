# Isolated FOB robustness checks

Run the opt-in HTTP pressure test only with Docker available:

```sh
cd backend
mvn -B '-Dtest=FobRobustnessHttpStressTest,FobPurchasePostgresIntegrationTest,QuotationRoleConsistencyPostgresIntegrationTest,FobSourceParserTest' '-Dquotation.fob.stress=true' test
```

`FobRobustnessHttpStressTest` starts its own random-port Spring server, disposable PostgreSQL 16.4 database and Redis 7.4 session service. It accepts no target URL and cannot load production fixtures. HTTP session cookies are permitted on HTTP only in this test's isolated context; production settings are unchanged.

The default pressure phase lasts 120 seconds with 50 independently authenticated accounts (10 administrators, 10 finance, 30 employees). It continuously checks shared FOB sources, finance settings, quote snapshots, scoped lists and review polling while administrators create procurement rows and race to mark an employee quotation as inspected. Assertions check returned data, not just HTTP status. The fixture has 100 bulk-imported SKUs and 50 historical snapshots; this is a focused concurrency test, not a production-volume capacity benchmark.

Other stages cover malformed fields, ambiguous prices, 101-row rejection, stale-preview atomicity, repeat imports, unauthorized marking, and a database trigger that deliberately fails a batch after another row is inserted. Both data and audit must roll back, and retry must succeed. That injected HTTP 500 is expected and recorded separately from load requests.

The report is `backend/target/fob-robustness-stress.json`: account/request counts, peak in-flight requests, per-operation P50/P95/P99/max, completed assertions and final pass status. PostgreSQL and Redis are removed automatically. It does not exercise production Nginx/TLS or production data volume; do not present these timings as end-to-end production capacity.

Default CI additionally runs deterministic PostgreSQL regressions for reverse-order overlapping inserts and eight simultaneous stale-preview updates, including companion-row rollback and exact audit counts. Frontend tests independently check 2,000 numeric cases (6,000 quantity assertions) and 20 responses completing in reverse order. No production business data is written by this workflow.

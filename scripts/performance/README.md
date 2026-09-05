# Isolated quotation performance gate

The fixture refuses to run unless the database is named `quotation_perf`. It creates deterministic synthetic users, 10,000 purchase SKUs, published US/Australia logistics rows, finance settings and 2,000 quotation records.

Build the backend runtime image from the already verified JAR with `backend-runtime.Dockerfile`, then run it with the production frontend image using `deploy/docker-compose.yml` plus `deploy/docker-compose.uat.yml`. Use a unique Compose project, networks, volumes, test-only passwords, database name `quotation_perf`, and loopback port `18088`. After the backend is healthy:

1. Pipe `seed.sql` into `psql` inside the isolated PostgreSQL container.
2. Restart only the isolated Redis container after reseeding. The fixture writes SQL directly and therefore cannot emit the application's normal published-logistics cache invalidation event.
3. Run `pnpm test:performance` with `PERF_BASE_URL` set to that isolated loopback port. The script accepts only a loopback URL, independently checks/approves the seeded billing samples, loads the current logistics revision, and submits real single/bundle quotation saves with calculated freight. Never point it at a developer's existing local stack.
4. Keep `artifacts/performance-result.json` as the P50/P95/P99 and error-rate evidence.

Defaults are 60 seconds warm-up followed by 10 minutes at 30 concurrent users. Short diagnostic runs may override `PERF_WARMUP_SECONDS` and `PERF_DURATION_SECONDS`; release evidence must use the defaults.

## 50-user multi-role and abnormal gate

For the extended gate use `PERF_USERS=50`, `PERF_WARMUP_SECONDS=60`, `PERF_DURATION_SECONDS=600`, `PERF_BASE_URL=http://127.0.0.1:18098` and an explicit `PERF_OUTPUT`. `monitor-load.mjs` wraps the same workload and records container/database samples for the isolated `quotation-interaction-perf` project. Each logical operation waits 250ms; this is a dense workload, not 50 idle sessions.

Run hot reads before changing the seeded employee roles. Apply `quotation-role-mix.sql` only to `quotation_perf`, then set `PERF_ROLE_MIX=true` and `PERF_READ_ONLY=false`: PERF01–40 are sales, 41–44 purchase, 45–47 finance, 48–50 logistics. The script does not itself grant roles. Reinitialize the isolated fixture before rerunning an all-sales scenario.

`quotation-abnormal.mjs` runs 17 separate 50-request rejection/race bursts plus an idempotent replay check. It creates isolated products, quotes, drafts and providers. Run it outside the measured normal workload. `quotation-eligibility-parity.mjs <snapshot.json>` compares direct eligibility with the existing finance channel list over all supplied countries and relations.

`purchase-search-index-tuning.sql` is an optional isolated experiment for existing GIN indexes. It refuses any database other than `quotation_perf`. It is not a production migration or authorization to change production indexes. Validate bulk-import overhead separately before production adoption. Keep failed baseline reports and distinguish resource increases, index maintenance, and application changes.

The 2026-09-05 deep-test report is in `docs/quotation-50-user-deep-test-2026-09-05.md`. The report generator expects the four named phase JSON files and their resource JSON files under `artifacts/performance`.

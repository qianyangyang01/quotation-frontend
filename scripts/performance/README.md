# Isolated quotation performance gate

The fixture refuses to run unless the database is named `quotation_perf`. It creates deterministic synthetic users, 10,000 purchase SKUs, published US/Australia logistics rows, finance settings and 2,000 quotation records.

Build the backend runtime image from the already verified JAR with `backend-runtime.Dockerfile`, then run it with the production frontend image using `deploy/docker-compose.yml` plus `deploy/docker-compose.uat.yml`. Use a unique Compose project, networks, volumes, test-only passwords, database name `quotation_perf`, and loopback port `18088`. After the backend is healthy:

1. Pipe `seed.sql` into `psql` inside the isolated PostgreSQL container.
2. Restart only the isolated Redis container after reseeding. The fixture writes SQL directly and therefore cannot emit the application's normal published-logistics cache invalidation event.
3. Run `pnpm test:performance` with `PERF_BASE_URL` set to that isolated loopback port. The script accepts only a loopback URL, independently checks/approves the seeded billing samples, loads the current logistics revision, and submits real single/bundle quotation saves with calculated freight. Never point it at a developer's existing local stack.
4. Keep `artifacts/performance-result.json` as the P50/P95/P99 and error-rate evidence.

Defaults are 60 seconds warm-up followed by 10 minutes at 30 concurrent users. Short diagnostic runs may override `PERF_WARMUP_SECONDS` and `PERF_DURATION_SECONDS`; release evidence must use the defaults.

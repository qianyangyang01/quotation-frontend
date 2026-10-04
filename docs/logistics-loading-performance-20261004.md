# Logistics loading performance

Production diagnosis found a 3.4-second workspace backend request and a rules response of 1.19 MB that sometimes took almost 10 seconds to reach the client despite a 0.26-second upstream response.

The workspace API now returns the version fields used by the list and history table. Full review evidence remains available through version detail, and internal backups/fingerprints continue using the full workspace. V55 maintains the existing country-count semantics in a generated column, including existing versions and subsequent payload edits. It does not change version payloads, quotation snapshots, review state or finance settings.

Published logistics endpoints use weak ETags, allowing Tomcat JSON compression while accepting both previous strong validators and new weak validators. Revision checks remain unchanged. Static JavaScript/CSS/SVG assets enable gzip in the quotation frontend only; the shared training edge configuration is not changed.

Initial quotation loading requests the current and selected countries; other countries remain in the catalog and load on selection. Country slices are reused only within matching publication revision, attribute and channel scope, including empty results. Invalidated or aborted responses cannot replace the displayed rules. The visible common country loads automatically after product readiness or when returning to common mode.

Validation includes PostgreSQL migration/backfill and update coverage, unchanged historical snapshots, compact/full workspace parity, real Tomcat gzip/identity content parity and conditional 304 requests, plus frontend cache invalidation, selection and regression tests.

Rollback: retain the prior versioned application images and compose file. V55 is additive; an application rollback may leave the unused generated column in place. Never remove historical versions or rewrite quotation/review records to roll back this performance change.

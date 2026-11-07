# Workflow: production readiness review

## Entry criteria

Use for a readiness review of a specific service, change, or deployable slice. Treat the current repo as a scaffold until operational evidence is found.

## Review areas

1. **Architecture:** service ownership, dependencies, contracts, cross-service calls, timeouts, failure isolation, compatibility, deployment topology.
2. **Security:** authentication boundary, authorization/ownership, IDOR, secrets, sensitive fields, rate limiting only if approved/configured, audit needs.
3. **Reliability:** bounded timeouts/retries, idempotency, duplicate delivery, circuit breaking only if selected/configured, graceful degradation, concurrency.
4. **Data:** constraints, transactions, migration/backfill/rollback, backup/restore, retention, pool sizing, query/index evidence.
5. **API:** validation, stable errors, compatibility, guest scope, correlation ID, safe async semantics.
6. **Messaging:** outbox/inbox, dedupe, retry/DLX, poison message handling, schema evolution, payload privacy.
7. **Observability:** structured logs, `X-Correlation-Id` propagation, metrics, health/readiness, traces, actionable alerts, redaction. Actuator is configured; do not infer metrics/tracing/alerts from that alone.
8. **Testing:** unit/integration/contract/security/failure/concurrency coverage and reproducible CI evidence.
9. **Operations:** runtime config, secret management, deployment/rollback, capacity, disaster recovery, SLO/ownership/runbooks. Current project environment/SLO decisions are TBD unless a newer source resolves them.

## Steps

1. Load architect + security reviewer profiles and relevant technology skills.
2. Inspect actual artifact/config/tests and canonical docs for the exact deployable scope.
3. For each item, record `PASS` with evidence, `GAP`, `TBD`, or `NOT APPLICABLE` with reason.
4. Prioritize blockers by likelihood/impact and distinguish code changes from operational decisions.
5. Avoid turning recommendations into selected architecture; mark improvements `PROPOSED` and identify owner/decision needed.

## Exit criteria and output

Return a concise readiness matrix with evidence, blockers, unresolved decisions, and an ordered remediation plan. Do not claim production-ready without deployment/operations evidence.

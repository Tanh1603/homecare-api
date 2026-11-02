# Workflow: add or change a RabbitMQ event

## Entry criteria

Use only for a requested asynchronous integration with an identified producer/consumer or a contract review. RabbitMQ is the approved broker direction; exact event meaning/topology/payload policy still needs evidence.

## Steps

1. Load `agents/architect.md` or `agents/backend-developer.md`, `skills/rabbitmq-events/SKILL.md`, and project baseline.
2. Read ADR-003, related use case/AC, context map, target service contracts/build/config, and current producer/consumer code.
3. Define event owner, business meaning, trigger, schema/version, producer, consumer(s), data classification, and whether a command/request is actually synchronous instead.
4. Confirm Topic Exchange + DLX and shared event envelope direction; mark exact exchange/queue/routing keys, retry limits, retention, ordering, and compatibility as `TBD` unless approved in docs.
5. Plan local transaction + outbox, consumer idempotency/deduplication, acknowledgement, poison-message path, and observability.
6. Implement only within approved scope and module conventions; do not leak persistence entities across contracts.
7. Test duplicate delivery, transient/permanent failure, invalid/old-version payload, broker downtime, and commit/publish race where relevant.
8. Audit for sensitive payload fields, retry loops, unbounded queues, and false exactly-once claims.

## Stop conditions

- No agreed event semantic/schema/owner, or downstream side effect would be based on an invented business rule.
- Reliability policy cannot be safely implemented because retry/DLX/transaction behavior is unresolved.

## Exit criteria and output

Producer/consumer behavior is idempotent and traceable; contract and policy statuses are explicit; tests and runtime limitations are reported.

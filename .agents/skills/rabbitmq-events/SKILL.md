---
name: rabbitmq-events
description: Designs or implements HomeCare RabbitMQ event flows using the approved reliability direction while checking unresolved topology and event contracts.
---

# RabbitMQ events

## Purpose

Apply reliable asynchronous messaging patterns without fabricating event semantics or mistaking the approved direction for a running broker integration.

## When to use

- A requested producer/consumer integration, event contract review, delivery failure analysis, deduplication, or outbox work.

## When not to use

- Synchronous request/response calls or unrelated persistence work. Do not select gRPC as a substitute without an approved decision.

## Prerequisites

Read ADR-003, system design, event-related use cases, DDD context map, relevant service docs, actual module dependencies/configuration, and open questions. RabbitMQ is selected; Topic Exchange + DLX, shared event DTO/library, envelope (`event_id`, `version`, `timestamp`), consumer `processed_events`, and producer `outbox_events` are approved design direction. Runtime, exact topology/payload, retry/retention, compatibility, and rollout remain to be verified/TBD.

## Core principles

- Define owner, event meaning, producer, consumers, schema/version compatibility, and delivery semantics before wiring code.
- Assume at-least-once delivery unless a documented contract says otherwise; make consumers idempotent.
- Use an outbox to couple local state change and publication intent; publish asynchronously with observable failure handling.
- Distinguish retryable from permanent failures; define bounded retry and DLQ handling without silently discarding poison messages.
- Do not promise ordering or exactly-once delivery without a design and evidence.
- Do not put sensitive data in event payloads without a requirement and security review.

## Implementation guidelines

1. Confirm event contract and state each unresolved topology/policy choice.
2. Identify local transaction boundary and outbox/inbox persistence owner.
3. Add producer/consumer using the existing module dependencies and conventions.
4. Implement duplicate handling, version tolerance, retry/DLX behavior, and logs/metrics per approved policy.
5. Test duplicates, redelivery, invalid payload, broker unavailable, and publication/ack failure where applicable.

## Common mistakes

- Treating every domain change as an event.
- Publishing before the DB transaction commits without outbox guarantees.
- Acknowledging before durable processing or marking a message processed before business transaction success.
- Sharing JPA entities as event DTOs or coupling consumers to producer database schema.
- Assuming the README's intended topology exists in runtime configuration.

## Validation checklist

- [ ] Contract and owner are identified.
- [ ] Event envelope/version and deduplication align with ADR-003.
- [ ] Retry, DLX, retention, ordering, and sensitive-data behavior are explicit or marked TBD.
- [ ] Failure and duplicate cases are tested or listed as a limitation.

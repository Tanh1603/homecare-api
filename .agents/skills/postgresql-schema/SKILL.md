---
name: postgresql-schema
description: Reviews or changes service-owned PostgreSQL persistence, JPA mappings, constraints, indexes, and migration impact for HomeCare.
---

# PostgreSQL schema and service data ownership

## Purpose

Keep relational persistence consistent with documented aggregates and service ownership while treating candidate ERDs as proposals until approved.

## When to use

- JPA model, schema, query/index, transaction, data integrity, database ownership, or migration review.

## When not to use

- Cross-service reporting or a request to choose a migration platform without architecture review.

## Prerequisites

Read the relevant aggregate design, data dictionary, ERD, database design, system data architecture, open questions, target service build/config, and `../../../services/README.md`. Project architecture/data docs are under `../../../../docs/02-architecture/`.

## Core principles

- A service owns its data. No cross-service database queries, JPA entity references, or foreign keys.
- Use local relational constraints to protect approved invariants; do not infer domain rules from a candidate ERD.
- Keep transaction boundaries local. Cross-service consistency needs an explicit API/event workflow.
- Index actual filter/join/sort/query patterns; avoid speculative indexes and arbitrary client-controlled sort expressions.
- Current docs describe Hibernate `validate` for data-owning services. Flyway is not configured in the audited code; do not assume it is selected.
- Redis is not observed/configured; do not add it as cache or datastore without an approved use case and policy.

## Implementation guidelines

1. Identify aggregate owner and exact invariant/AC.
2. Compare proposed change against current ERD/data dictionary and actual schema strategy.
3. Evaluate nullability, uniqueness, FK scope, deletion, concurrency, query plan, and data migration/backfill.
4. Confirm migration tool and rollout/rollback approach before creating migration files.
5. Test persistence behavior and constraints against the owning service.

## Common mistakes

- Cross-service foreign keys or cascading JPA relationships.
- Adding columns/status fields for unresolved product policy.
- Assuming Hibernate `validate` creates schema or a migration tool exists.
- Changing data shape without compatibility/backfill/rollback consideration.

## Validation checklist

- [ ] Ownership and approved invariant identified.
- [ ] Candidate/proposed schema choices labeled correctly.
- [ ] No cross-service database coupling introduced.
- [ ] Migration strategy and rollout are explicit or marked TBD.
- [ ] Constraints, indexes, and concurrency behavior have a reason.

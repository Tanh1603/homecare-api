---
name: ddd-traceability
description: Traces a HomeCare backend change from requirements and use cases through bounded contexts, aggregates, ownership, contracts, implementation, and tests.
---

# DDD and traceability

## Purpose

Keep implementation aligned with documented domain intent while preserving uncertainty and avoiding service-per-entity design.

## When to use

- New feature planning, service ownership decisions, aggregate/domain changes, cross-service workflows, or traceability audits.

## When not to use

- Mechanical fixes with no domain behavior; use the focused implementation/review skill.

## Prerequisites

Read SRS, relevant use case and acceptance criteria, traceability matrix, DDD index/context map/aggregate design, domain model, system design, and relevant ADR/open question.

## Core principles

- SRS is source for FR/NFR; use cases for flows/exceptions; acceptance criteria for detailed acceptance checks. Check current document notes in case they have changed.
- A bounded context is a modeling boundary; it is not automatically a deployed service.
- Model an aggregate around invariants and transactional consistency, not one aggregate per table/entity.
- Service boundaries and ownership in current system design are largely `PROPOSED`; do not overstate approval.
- Use opaque references across service boundaries; data owner decides its own state.
- Preserve the project's status vocabulary: `OBSERVED`, `SOURCE`, `APPROVED`, `PROPOSED`, `TBD`, `CONFLICT`.

## Implementation guidelines

1. Identify IDs: requirement → story/AC → use case.
2. Identify the candidate context, actor, aggregate, invariant, and owner.
3. Locate approved API/event/data contract and service module. If absent, identify the missing decision/contract.
4. Separate functional requirements from candidate design and implementation evidence.
5. Update traceability docs only when documentation changes are in scope.

## Common mistakes

- Treating every listed service/module as a finalized bounded context.
- Inventing status transitions, role mappings, cardinalities, or workflow rules.
- Creating shared domain types or direct cross-service database joins.
- Using an ERD candidate as approved DDL.

## Validation checklist

- [ ] Requirement and acceptance trace exists or gap is explicit.
- [ ] Owner/context and aggregate boundary are evidence-based.
- [ ] Approved decisions, proposals, TBDs, and conflicts are distinct.
- [ ] No domain policy was copied into generic technology guidance.

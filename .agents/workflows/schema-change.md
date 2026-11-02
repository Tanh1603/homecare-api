# Workflow: change service-owned schema

## Entry criteria

Use for a requested persistent data change tied to an approved requirement/behavior and a named owning service. A schema/migration task must explicitly include the data change; do not add one opportunistically.

## Steps

1. Load `agents/database-engineer.md`, `skills/postgresql-schema/SKILL.md`, and the project/security-data rules.
2. Read aggregate design, relevant ERD, data dictionary, system data architecture, open questions, target module schema/config, and actual migration setup.
3. Confirm ownership, invariant, nullability/cardinality, unique constraints, FK scope, query patterns, concurrency, retention/privacy, and compatibility/backfill needs.
4. Check the migration mechanism and environment. Current docs describe Hibernate `validate`; do not assume Flyway or introduce it without a decision.
5. Propose the smallest schema change and rollout/rollback/backfill strategy. Mark unapproved design details `PROPOSED`/`TBD`.
6. Only after approach is in scope and agreed, implement the owning-service persistence change and migration using the established mechanism.
7. Test constraints, data mapping, failure and concurrent behavior relevant to the invariant.
8. Review for cross-service FK/entity/query, sensitive data exposure, and old/new version compatibility.

## Stop conditions

- Data ownership, business invariant, migration platform, production rollout, or required backfill lacks a necessary decision.
- The change would require direct cross-service writes or an unapproved architecture change.

## Exit criteria and output

Schema change is local to its owner, tied to an approved invariant, safely migratable, and validated. Report data impact, migration/rollback, test evidence, and outstanding decisions.

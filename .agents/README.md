# HomeCare AI agent system

This directory holds reusable guidance for AI-assisted backend work. It complements the repository-root `AGENTS.md`; it does not replace product requirements, approved architecture decisions, or API contracts under `../../docs/`.

## Audit snapshot

Observed backend baseline: this directory's parent (`../`) is a Gradle multi-project build with Java 21, Spring Boot 4.1.2, and seven service modules: identity, household, provider, booking, commerce, review, and notification. Spring MVC, validation, Spring Security, Actuator, and the Spring Boot test starter are configured centrally. The data-owning modules declare Spring Data JPA and PostgreSQL; most modules also declare Spring AMQP. Identity declares Firebase Admin. This is scaffold/configuration evidence, not evidence that business endpoints, Firebase verification, messaging topology, or production operations are implemented.

The architecture docs mark microservices, Spring Cloud API Gateway, Gateway Firebase ID-token verification, Spring Security RBAC/resource authorization, and RabbitMQ Topic Exchange + DLX/event reliability foundation as approved directions. Service boundaries and many runtime details remain proposed or TBD. REST conventions such as `/api/v1`, JSON/DTO boundary, pagination defaults, and `X-Correlation-Id` are approved; endpoint contracts remain separately governed by docs.

No implementation/configuration evidence was found for gRPC, Redis, Flyway, Eureka/service discovery, a deployed Spring Cloud Gateway, Testcontainers, or a full metrics/tracing stack beyond Actuator. Treat these as `TBD` or `PROPOSED` according to the relevant source document. Do not add them just because a skill mentions them.

## Layout and loading

- `rules/` — short project constraints; root `AGENTS.md` points backend work to the baseline rule. Load specialist rules when relevant.
- `agents/` — role cards for selecting or prompting a specialist. They are portable reference profiles; Codex or Antigravity does not necessarily instantiate them automatically.
- `skills/` — reusable task knowledge in `SKILL.md` format, discovered by compatible skills-aware tools.
- `workflows/` — task checklists with explicit inputs, steps, stop conditions, and outputs. Load only the workflow matching the current task.

For each task, use the smallest useful set: project baseline + one role + relevant skill(s) + one workflow. Do not copy whole source documents into these files; follow links to the canonical docs.

## Architecture status

Use these labels as the project uses them:

- `OBSERVED`: directly present in code/config/docs.
- `SOURCE`: stated in a project source or requirement, not necessarily approved for implementation.
- `APPROVED`: explicitly confirmed by the project owner; still distinguish design direction from implementation evidence.
- `PROPOSED`: candidate design that needs review/approval.
- `TBD`: unresolved detail.
- `CONFLICT`: sources disagree; preserve and report the disagreement.

When sources conflict, do not resolve the conflict by guessing. Cite the exact file and section, and pause only the dependent decision while continuing independent work.

## Canonical references

- Requirements: `../../docs/01-requirements/SRS.md`; use cases: `../../docs/01-requirements/use-cases.md`; acceptance criteria: `../../docs/01-requirements/acceptance-criteria.md`.
- Traceability: `../../docs/00-project/traceability-matrix.md`.
- Architecture: `../../docs/02-architecture/system-design.md`, `../../docs/02-architecture/architecture-overview.md`, and relevant ADRs in `../../docs/02-architecture/ADR/`.
- DDD: `../../docs/02-architecture/ddd/README.md` and linked analysis/design files.
- Data: `../../docs/02-architecture/db/README.md`, `database-design.md`, `system-data-architecture.md`, `data-dictionary.md`, and relevant ERDs.
- API: `../../docs/03-api/api-conventions.md`, `api-catalog.md`, `service-boundary-audit.md`, and relevant service docs.
- Backend module map and setup notes: `../services/README.md`, `../settings.gradle`, and `../build.gradle`.

## Selection matrix

| Task | Role profile | Skills | Workflow |
|---|---|---|---|
| Architecture or service boundary | `agents/architect.md` | `skills/ddd-traceability/SKILL.md` | `workflows/design-change.md` |
| Implement within an existing service | `agents/backend-developer.md` | `skills/spring-boot/SKILL.md`, `skills/gradle-multiproject/SKILL.md` | `workflows/implement-service.md` |
| Add or revise an API operation | `agents/api-engineer.md` | `skills/rest-api-contracts/SKILL.md` | `workflows/implement-api.md` |
| Data/schema change | `agents/database-engineer.md` | `skills/postgresql-schema/SKILL.md` | `workflows/schema-change.md` |
| RabbitMQ event integration | `agents/backend-developer.md` | `skills/rabbitmq-events/SKILL.md` | `workflows/rabbitmq-event.md` |
| Authentication/authorization | `agents/security-reviewer.md` | `skills/firebase-spring-security/SKILL.md` | `workflows/security-review.md` |
| Test planning or implementation | `agents/test-engineer.md` | `skills/backend-testing/SKILL.md` | `workflows/write-tests.md` |
| Audit an implementation | relevant reviewer role | only skills needed to understand the change | `workflows/contract-audit.md` or `workflows/production-readiness.md` |

There are no dedicated generic skills for gRPC, Redis, Flyway, Eureka, or Spring Cloud runtime configuration yet: their project adoption/configuration is unconfirmed. First use the architect profile and design workflow to check current ADRs/open questions. Add a focused skill only after a repeatable, approved project workflow exists.

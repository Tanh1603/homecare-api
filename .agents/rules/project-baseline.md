# Project baseline rule

Apply this rule to backend work. The referenced project documents are authoritative; this rule is an index and guardrail, not a replacement for them.

1. Before changing backend behavior, trace the task through requirement → use case/acceptance criteria → DDD/context → contract/ADR → owning service. Record missing links as `TBD`; do not invent them.
2. Separate `OBSERVED`, `SOURCE`, `APPROVED`, `PROPOSED`, `TBD`, and `CONFLICT`. An approved direction is not proof of an implemented component.
3. Keep service boundaries/data ownership as documented. A service must not query another service's database or import another service's JPA entity/business domain model. Use an approved API/event contract; if none exists, report the gap instead of inventing one.
4. The backend is a Gradle multi-project build in `homecare-api/`; use the wrapper and actual module names. Do not assume the repository root is a build root.
5. Consult `../../../docs/03-api/api-conventions.md` before REST changes. Do not add an endpoint absent from the approved scope/contract or silently widen guest access.
6. Consult `../../../docs/02-architecture/ADR/ADR-003-messaging-broker.md` before RabbitMQ changes. RabbitMQ direction and event reliability foundation are approved; exact topology/payload, retries, retention, compatibility, and runtime behavior may remain TBD.
7. Firebase ID-token verification at the Spring Cloud Gateway and authorization in resource-owning Spring services are approved directions. Gateway implementation and trusted caller-context propagation are not established; never trust client-supplied user/role identity.
8. Do not introduce gRPC, Redis, Flyway, Eureka, Testcontainers, circuit breakers, or other new infrastructure as though already selected. Verify the latest ADR/open questions, then label any new choice `PROPOSED` and seek an explicit decision where required.
9. Do not add credentials, real tokens, production PII, or secret-bearing examples.
10. Keep the task scope. Do not implement unrelated business behavior, rewrite contracts/ADRs, or expand infrastructure while doing a focused code task.

## Current backend facts to re-check

- `../build.gradle` currently configures Java 21, Spring Boot 4.1.2, Spring MVC, validation, Security, Actuator, and the Spring Boot test starter for service projects.
- `../settings.gradle` includes seven services: identity, household, provider, booking, commerce, review, notification.
- `../services/README.md` describes service ownership boundaries and environment variables. Verify each claim against the module before relying on it.
- PostgreSQL/JPA, Firebase Admin (Identity), and Spring AMQP dependencies exist in selected modules; that alone does not prove integrations are complete.
- Hibernate `validate` is documented for data-owning services. No Flyway setup was found in the audited backend build/config.

If code and documentation disagree, report `CONFLICT` with file paths and do not silently change the architecture source of truth.

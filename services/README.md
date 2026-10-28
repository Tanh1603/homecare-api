# HomeCare API services

This directory contains independent Spring Boot applications managed by the
root Gradle multi-project build. Each service owns its API, persistence model,
configuration, and deployable artifact. Cross-service data access must use
service APIs or RabbitMQ contracts; services must not query another service's
database directly.

| Gradle project | Responsibility | Additional dependencies |
|---|---|---|
| `:services:identity-service` | Internal profile and RBAC role mapping; Firebase identity integration | Spring Data JPA, PostgreSQL JDBC, Firebase Admin SDK |
| `:services:household-service` | Household, device, warranty, maintenance, and repair history | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:provider-service` | Providers, technicians, service offerings, and availability | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:booking-service` | Service requests, booking, assignment, and lifecycle | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:commerce-service` | Merchant, catalog, inventory, order, and sandbox payment | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:review-service` | Service reviews and their lifecycle | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:notification-service` | Notification inbox and delivery processing | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:assistant-service` | Spring HTTP/security boundary for assistant workflows | No persistence or messaging dependency until required |

All services inherit Spring MVC, validation, Spring Security, Actuator, Java
21, and the Spring Boot test starter from the root build. PostgreSQL, RabbitMQ,
Firebase Admin, and service-specific dependencies are declared in each module.

## Design notes

- The Spring Security dependency is a boundary scaffold; role/resource rules,
  Firebase token verification placement, and security configuration remain to
  be implemented and reviewed.
- Firebase Admin SDK is included in Identity for integration readiness. No
  credentials or Firebase project configuration are stored in this repository.
- `assistant-service` is a Spring Boot API boundary only. The AI runtime,
  orchestration framework, LLM provider, and RAG implementation are not selected
  or implemented by this scaffold.
- Redis, payment-provider SDKs, database migrations, and API documentation
  tooling are omitted until their usage and contracts are defined.

Data-owning services require `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` and use
Hibernate `validate` so startup never creates or mutates schema implicitly.
RabbitMQ integrations default to localhost and can be pointed elsewhere with
`RABBITMQ_HOST` and `RABBITMQ_PORT`. Run one configured service with
`./gradlew :services:<service-name>:bootRun`, or build artifacts with
`./gradlew bootJar`. Generated files are centralized under root `build/`,
partitioned by service in `build/services/<service-name>/`.

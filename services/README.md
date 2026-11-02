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
| `:services:review-service` | Service and product review capability; implementation and evidence contracts pending | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |
| `:services:notification-service` | Notification inbox and delivery processing | Spring Data JPA, PostgreSQL JDBC, RabbitMQ |

All services inherit Spring MVC, validation, Spring Security, Actuator, Java
21, and the Spring Boot test starter from the root build. PostgreSQL, RabbitMQ,
Firebase Admin, and service-specific dependencies are declared in each module.

## Design notes

- The Spring Security dependency is a boundary scaffold. Gateway Firebase
  ID-token verification and service-level RBAC/resource checks are approved
  directions (ADR-004/005); Gateway, trusted caller context, role provisioning,
  and business security configuration are not implemented in this scaffold.
- Firebase Admin SDK is included in Identity for integration readiness. No
  credentials or Firebase project configuration are stored in this repository.
- There is no Spring `assistant-service` module in the current Gradle build.
  ADR-002 selects a separate Python/FastAPI AI service boundary; AI runtime,
  API, orchestration, LLM provider, and RAG implementation remain deferred.
- VNPAY sandbox is the approved first development payment provider through a
  Strategy adapter (ADR-006). The adapter, callback verification, reservation
  schema, and expiry/recovery policy are not implemented by this scaffold.
- Liquibase migration jobs and isolated dev/test provisioning are implemented
  in Phase 2. Business migrations remain gated by approved schemas.
- Redis, payment-provider SDKs and OpenAPI artifacts remain outside this phase.

Data-owning services require `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` and use
Hibernate `validate` so startup never creates or mutates schema implicitly.
RabbitMQ connection properties default to localhost and can be pointed elsewhere with
`RABBITMQ_HOST` and `RABBITMQ_PORT`. Run one configured service with
`./gradlew :services:<service-name>:bootRun`, or build artifacts with
`./gradlew bootJar`. Generated files are centralized under root `build/`,
partitioned by service in `build/services/<service-name>/`.

Audit on 2026-11-02: `gradlew.bat projects` and `gradlew.bat bootJar` succeeded
for all seven service modules. This verifies project configuration and packaging,
not application startup, business tests, database connectivity, Firebase, RabbitMQ,
FCM, or payment integration. See the Phase 0 evidence in
[`service-boundary-audit.md`](../../docs/03-api/service-boundary-audit.md#9-phase-0--audit-be-core-ngày-2026-11-02).

## Phase 2 — reproducible local setup

Run all commands below from `homecare-api` in PowerShell. Java toolchain 21 is
required; the Gradle wrapper remains 9.7.1 and Spring Boot remains 4.1.2.
Liquibase 5.0.4 is resolved from the Boot BOM; picocli 4.7.7 is a CLI-only
dependency. Migration libraries are not packaged into service bootJars.
Compose pins PostgreSQL 17.11 and RabbitMQ 4.2.9 images by digest. Local ports
bind to loopback. RabbitMQ management UI is not exposed.

```powershell
.\scripts\dev.ps1 init
.\scripts\dev.ps1 up
.\scripts\dev.ps1 provision
.\scripts\dev.ps1 migrate
.\scripts\dev.ps1 run -Service identity -Port 8080
```

`init` generates separate random passwords into ignored `.env`; it refuses to
overwrite an existing file. `.env.example` documents variables without usable
credentials. `provision` creates `hc_dev_<owner>` databases and separate
`<database>_app` / `<database>_migrator` login roles. Existing names fail rather
than reset data; after partial failure inspect provisioned roles/databases
before retrying. The script restores the caller's environment on exit.

Runtime accounts have CONNECT, public-schema USAGE and DML only; PUBLIC gets
no database/schema access or temporary-table privileges. Migrators own their
database/schema. Liquibase history/locks are in private `migration` schema,
not readable or writable by runtime users. Never use provisioner/migrator
credentials in an application process.

Provisioning targets a dedicated HomeCare instance: it also revokes PUBLIC
access to the maintenance databases `postgres` and `template1`. Do not run it
on an unrelated shared cluster without an operator review of existing access.

Each service owns `src/main/resources/db/changelog/db.changelog-master.xml`.
It is deliberately empty until its business schema is approved. Add explicit
`<include file="...sql" relativeToChangelogFile="true"/>` entries for immutable
Liquibase formatted SQL changesets; do not edit an applied changeset to fix a
checksum failure. Run the separate `:services:<owner>-service:migrate` job
before starting the application; that task requires
`LIQUIBASE_COMMAND_URL`, `LIQUIBASE_COMMAND_USERNAME`, and
`LIQUIBASE_COMMAND_PASSWORD`. Metadata schema is fixed by the Gradle task.
Hibernate remains `validate`; no SQL init or application-time migration.
Rollback means a compatible application rollback; destructive schema rollback
needs reviewed SQL and backup, not a convenience reset command.

All seven applications currently deny HTTP access, including health, with no
default local account. Only `health` is exposed by Actuator; details/components
and JMX are disabled. Readiness includes DB and, outside Identity, RabbitMQ.
There is no public health exception. Phase 3 must install approved mTLS/Firebase
and an authenticated operations/probe rule before external HTTP operations.
Startup tests inspect readiness in-process and verify external HTTP stays denied.
The Identity API infrastructure is the pilot: safe error envelope, validation,
strict unknown JSON fields, pagination and correlation propagation/cleanup.
No business endpoints or shared internal framework were added. Other services
will adopt that infrastructure when their first approved API slice is implemented.

## Tests

```powershell
.\gradlew.bat projects
.\gradlew.bat test bootJar
# New, dedicated database set for each integration run:
.\scripts\dev.ps1 provision -DatabasePrefix hc_test_example1
.\scripts\dev.ps1 migrate -DatabasePrefix hc_test_example1
.\scripts\dev.ps1 test -DatabasePrefix hc_test_example1
```

`test` contains seven Identity tests: real random-port HTTP fixture tests plus
correlation cleanup/outbound unit tests. The other six `test` tasks have no
unit sources; this is not evidence of business coverage. Test-only controllers
and the synthetic principal are not present in bootJars.

`integrationTest` is separate and always executes (no up-to-date shortcut).
It fails when the test prefix, credentials, DB or broker are absent. It never
substitutes H2, mocks or a skipped test for missing infrastructure. Seven
`StartupIT` classes start the actual application with runtime DB credentials.
Identity's infrastructure suite additionally tests all owner isolation grants,
Liquibase rerun/checksum behavior, Hibernate detecting a missing test-only table,
and a broker-confirmed publish/read/ack on an exclusive temporary queue. These
checks do not verify approved business event topology or business invariants.
Reports: `build/services/<service>/reports/tests/{test,integrationTest}/index.html`.

Docker is the selected reproducible dev/test path, not an application requirement.
An existing PostgreSQL 17 / RabbitMQ installation can supply the same environment:
run `scripts/provision.sql` with `psql -v ON_ERROR_STOP=1` as an operator, supplying
the database/runtime/migrator identifiers and passwords via protected stdin;
then invoke migration tasks and app tasks with their respective credentials.
The provided helper targets local Compose ports; direct `DB_URL` supports another
host. Never commit environment-specific connection details.

To stop local infrastructure: `.\scripts\dev.ps1 down`. The database volume is
preserved. No automatic database/role/volume deletion is supplied. WSL/Docker
must be healthy before `up`; installation/virtualization failures are environment
failures, not application or dependency-version failures.

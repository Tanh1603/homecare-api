# HomeCare API services

This directory contains independent Spring Boot applications managed by the
root Gradle multi-project build. Each service owns its API, persistence model,
configuration, and deployable artifact. Cross-service data access must use
service APIs or RabbitMQ contracts; services must not query another service's
database directly.

| Gradle project | Responsibility | Additional dependencies |
|---|---|---|
| `:services:gateway-service` | HTTPS boundary, Firebase verification, exact routes, caller-header sanitation, mTLS forwarding | Spring Cloud Gateway Server WebMVC, Firebase Admin SDK; no database |
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

- Phase3 implements Gateway/Identity Firebase verification, mTLS and self-profile
  authorization. Other resource services remain deny-all until their approved
  API slices implement workload/actor/resource checks. Local evidence is not
  Firebase live or all-domain authorization evidence.
- Firebase Admin SDK verifies at both Gateway and Identity. Credentials/project
  configuration are injected externally; no login/password store is implemented.
- There is no Spring `assistant-service` module in the current Gradle build.
  ADR-002 selects a separate Python/FastAPI AI service boundary; AI runtime,
  API, orchestration, LLM provider, and RAG implementation remain deferred.
- VNPAY sandbox is the approved first development payment provider through a
  Strategy adapter (ADR-006). The adapter, callback verification, reservation
  schema, and expiry/recovery policy are not implemented by this scaffold.
- Liquibase migration jobs and isolated dev/test provisioning are implemented
  in Phase 2. Business migrations remain gated by approved schemas.
- Redis and payment-provider SDKs remain outside this phase. Phase3b adds only the approved Identity public OpenAPI.

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
At Phase2 these indexes were empty; Phase3 Identity now includes its two approved
changesets, while the other six remain empty. Add explicit
`<include file="...sql" relativeToChangelogFile="true"/>` entries for immutable
Liquibase formatted SQL changesets; do not edit an applied changeset to fix a
checksum failure. Run the separate `:services:<owner>-service:migrate` job
before starting the application; that task requires
`LIQUIBASE_COMMAND_URL`, `LIQUIBASE_COMMAND_USERNAME`, and
`LIQUIBASE_COMMAND_PASSWORD`. Metadata schema is fixed by the Gradle task.
Hibernate remains `validate`; no SQL init or application-time migration.
Rollback means a compatible application rollback; destructive schema rollback
needs reviewed SQL and backup, not a convenience reset command.

In the Phase2 snapshot all seven applications denied HTTP access. Phase3 replaces
Identity's boundary as described below; the other six remain deny-all, with no
default local account. Only `health` is exposed by Actuator; details/components
and JMX are disabled. Readiness includes DB and, outside Identity, RabbitMQ.
There is no public health exception. Phase3 installs Identity/Gateway mTLS/Firebase;
an authenticated operations/probe deployment rule remains outside the public routes.
Startup tests inspect readiness in-process and verify protected HTTP stays denied.
The Identity API infrastructure is the pilot: safe error envelope, validation,
strict unknown JSON fields, pagination and correlation propagation/cleanup.
Phase2 added no business endpoints; Phase3 adds Identity's approved slice. Other services
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
startup checks start the actual applications with runtime DB credentials;
Phase3 replaces Identity's `StartupIT` with the stronger `TrustBoundaryIT`.
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

## Phase 3 — Gateway and Identity

Gateway uses approved Cloud BOM2025.1.2 (resolved MVC Gateway5.0.2), Boot4.1.2,
Java21 and Firebase Admin9.11.0. Gateway has no datasource or migration task.
Identity owns the approved profile/mapping/single-role/idempotency tables and
the two immutable Liquibase changesets. Run migrations before either application.
No Admin role-grant or account merge by email/phone is exposed.

The public Gateway listener is HTTPS. Its downstream targets must be HTTPS
origins, with hostname verification and a client certificate from the workload
bundle; redirects and mutation retry filters are not enabled. Identity requires
mTLS at its listener and validates the workload certificate CN against its
allowlist. Public self-profile operations accept only the `gateway` workload.
Internal resolve is never routed publicly. Every Identity operation verifies
the original Bearer again and reads local role/status/ownership; there is no
recursive resolve RPC and no caller-header or custom-role-claim trust.

Implemented routes: GET/PATCH `/api/v1/users/me`, POST
`/api/v1/users/me/bootstrap`; POST `/internal/v1/principals/resolve` is internal
only. Missing/invalid token→401; disabled app profile→403; self missing→404;
If-Match missing/stale→428/412; dependency unavailable→503. Bootstrap requires
an Idempotency-Key, body `{}`, defaults HOUSEHOLD_USER, and returns201 on creation
or200 when existing. Replay is authorized again and expires after24h. Expired
records are purged on a configurable interval (default60s); no replay grace period.
PATCH accepts only displayName/phone/address; omitted is unchanged and null clears.

Guest allowlist is the six discovery GET paths in ADR-005. They are edge route
permissions, not implemented Provider/Commerce catalog endpoints; those upstream
services still deny-all. Nested reviews/schedule, internal/Admin/payment and other
methods are not opened. Supplying an invalid token on a guest route gives401.
Actuator is not a public Gateway route; no public probe exception was introduced.

### Reproduce local security evidence without Google credentials

```powershell
# init/up only if not already initialized/running
.\scripts\dev.ps1 provision -DatabasePrefix hc_test_security1
.\scripts\dev.ps1 migrate -DatabasePrefix hc_test_security1
.\scripts\dev.ps1 test -DatabasePrefix hc_test_security1 -Service identity
.\gradlew.bat :services:gateway-service:test :services:gateway-service:bootJar
```

`TrustBoundaryIT` starts both real applications, PostgreSQL-backed Identity and
HTTPS→mTLS routing, using actual Firebase Admin token verification. It generates
ephemeral RSA keys/certificates with the Java21 keytool under ignored
`build/security-fixtures/`. The Google certificate and account lookup HTTP
responses are fixture transports; signatures, expiry/audience validation and
revocation checks execute in the SDK. No mock Spring principal replaces the
security filters. This is **local integration evidence, not Firebase live**.
Do not publish test output directories containing temporary private keys.

The test harness applies `scripts/seed-identity-test.sql` to create five synthetic
UID mappings for HOUSEHOLD_USER/SERVICE_PROVIDER/TECHNICIAN/MERCHANT/SYSTEM_ADMIN.
The operator-only script requires a `hc_test_*_identity` database and
`firebase_project_id` psql variable; it rejects duplicate inserts instead of
silently changing roles. It stores no passwords and creates no Google accounts.
Use the fixture project only for these fake accounts. Production/domain role
change and offboarding remain outside this phase.

### Running against a real Firebase dev project (not yet verified)

Configure Firebase email/password and ADC outside source. Fill the Phase3
environment variables documented in `.env.example`. Supply keystores for
Identity (`CN=identity`, serverAuth) and Gateway (`CN=gateway`, serverAuth and
clientAuth), valid server SAN hostnames, and a dedicated workload CA truststore.
Certificates/keys/passwords stay outside tracked files. The helper uses
`IDENTITY_TLS_KEYSTORE[_PASSWORD]` or `GATEWAY_TLS_KEYSTORE[_PASSWORD]` per process;
direct Gradle/bootJar execution uses generic `TLS_KEYSTORE[_PASSWORD]`.

```powershell
.\scripts\dev.ps1 run -Service identity -Port 8443
# separate terminal, same env configuration:
.\scripts\dev.ps1 run -Service gateway -Port 8080
```

Set IDENTITY_URL to the matching HTTPS Identity origin; PROVIDER_URL and
COMMERCE_URL must also be HTTPS origins even while their API slices are pending.
Set exact CORS_ORIGINS for the web client. Obtain ID tokens through the Firebase
client SDK; never paste them into source, logs, shell history or this README.
Do not set FIREBASE_AUTH_EMULATOR_HOST: the production verifier rejects unsigned
emulator mode. Local tests use a signed fixture instead.

Remaining live acceptance: real Google-issued tokens/ADC/network, certificate
rotation and deployment controls, real frontend sign-in/linking, and domain
authorization in each later service slice. No production-security claim is made.

### Phase 3b — operator setup and Google live acceptance

**Docker layout hiện hành (2026-11-03):** chỉ một `homecare-api/compose.yaml`.
Tám `services/<service-name>/Dockerfile` build từ bootJar riêng, dùng cùng Java21
digest/non-root/read-only runtime. Đã bỏ Dockerfile dùng chung và Compose override.
Build context là `homecare-api`; `.dockerignore` chỉ cho Dockerfiles và bootJars,
không gửi source/env/keys vào Docker build.

| Profile | Containers | Khi dùng |
|---|---|---|
| Mặc định | postgres, rabbitmq | Foundation/unit + integration local; không cần Firebase |
| core | identity, gateway + dependencies | Phase3b, cần env/ADC/mTLS thật |
| domains | household, provider, booking, commerce, review, notification + dependencies | Chạy scaffold riêng để phát triển; vẫn deny-all, không publish host ports |

```powershell
.\gradlew.bat bootJar
docker compose config --quiet
docker compose up -d --wait postgres rabbitmq
# Sau khi điền external env theo flow phía dưới:
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core build
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core up -d
# Tuỳ chọn: điền sáu env riêng và provision/migrate DB của từng owner trước.
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile domains build
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile domains up -d
# Dừng mọi profile, giữ nguyên database volume:
docker compose --profile '*' down
```

Env file của mỗi domain dùng template trong chính thư mục service, tham chiếu qua
`HOUSEHOLD_ENV_FILE`, `PROVIDER_ENV_FILE`, v.v. trong compose.env. Identity template
dùng hc_test_live; domain templates dùng hc_dev_<owner> — provision đúng prefix
hoặc sửa URL/account cho đúng DB đã có. Không dùng chung runtime account.
`env_file.required=false` cho phép validate/build hạ tầng khi chưa chuẩn bị env app;
không cung cấp credential mặc định: app thiếu DB/Firebase/TLS vẫn startup fail-closed.
Các secret path fallback chỉ là đường dẫn chưa tồn tại, không sinh credential giả.
Sáu domain listeners hiện chưa có mTLS/business API; không đổi Gateway HTTPS origins
sang HTTP để làm catalog chạy. Profiles này không phải evidence nghiệp vụ hoàn thành.
`dev.ps1 up` chỉ bật hạ tầng; `dev.ps1 down` dừng mọi profile và không xóa volume.

Hướng dẫn từng bước bằng tiếng Việt: [flow tạo key/env và chạy](#flow-tạo-keyenv-và-chạy-project).

Phase3b adds `liveFirebaseTest`, per-service Dockerfiles and the single `compose.yaml`;
it preserves `TrustBoundaryIT`. Public [OpenAPI](../contracts/openapi/identity-service.yaml)
contains only API-USR-001/002/003. Internal resolve remains in ADR-004.

**Secrets:** use a restricted operator directory outside all checkouts. The key
under docs/env is ignored; operator must place ADC outside the repo before live
startup. Never copy JSON into env/classpath/images or stage keys/env/token/PII.
Never publish docker inspect or expanded Compose config; use `config --quiet`.
Runtime ADC needs verification/account lookup in the same project. Revoke/update
tests can use separate `FIREBASE_OPERATOR_CREDENTIALS`; do not elevate runtime
permissions to satisfy tests. No emulator, no Firebase project/user auto-creation.

Copy these templates to the operator directory and replace PLACEHOLDER locally:

| Template | Consumer |
|---|---|
| `services/identity-service/.env.example` | Identity: DATABASE_URL, runtime DB credentials, project, TLS passwords |
| `services/gateway-service/.env.example` | Gateway: project, TLS passwords, HTTPS origins, exact CORS; no DB credentials |
| `scripts/.env.compose.example` | Compose: external env/ADC/keystore paths; Java21 digest is pinned in each Dockerfile |
| `scripts/.env.live.example` | Host test runner: Google login env, TLS, test DB, mutation/fault flags |

Load runner variables into the process starting Gradle separately from app
`env_file`. `DATABASE_URL` is JDBC; legacy DB_URL remains fallback.
`RABBITMQ_URL=amqp://host:port` supplies broker address; username/password/vhost
stay separate (no passwords in URLs). Old host/port are fallback. POSTGRES_PORT
and RABBITMQ_PORT still control Docker publishing. Identity/Gateway gain no AMQP.

Set random `DEV_PKI_PASSWORD` (24+ chars) through operator env, then run:

```powershell
.\scripts\new-dev-pki.ps1 -OutputDirectory $env:DEV_PKI_DIRECTORY
```

Target must be new/outside workspace. CA lasts30 days; Identity serverAuth,
Gateway serverAuth+clientAuth and rogue test cert last7 days. SAN includes
localhost/127.0.0.1 and Docker service DNS. Stores use the operator password.
Protect directory ACLs; populate service TLS passwords and runner cert paths.
Never mount CA private store/rogue keys into apps. Compose mounts only workload
store, truststore and ADC read-only at `/run/secrets/...`. Identity target is
`https://identity:8443`; Provider/Commerce HTTPS origins remain unavailable and
fail closed until implemented. No fake catalog and no public health exception.

From homecare-api, with existing ignored foundation .env and a NEW test prefix:

```powershell
.\gradlew.bat projects :services:identity-service:bootJar :services:gateway-service:bootJar
.\scripts\dev.ps1 provision -DatabasePrefix hc_test_live
.\scripts\dev.ps1 migrate -DatabasePrefix hc_test_live
.\scripts\live-preflight.ps1
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core config --quiet
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core build identity gateway
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core up -d identity
# Verify TLS with Gateway client certificate before continuing.
docker compose --env-file .env --env-file $env:COMPOSE_ENV_FILE --profile core up -d gateway
.\gradlew.bat :services:identity-service:liveFirebaseTest
```

Identity DATABASE_URL uses postgres:5432 and the provisioned DB; LIVE_DB_URL uses
host-published address. Provision refuses existing DBs; migrate preserves data.
Helpers keep seven-owner isolation; no new migrations in3b. Containers bind
0.0.0.0 internally, publish only localhost8443/8080. Authenticated requests are
readiness evidence. Direct Windows bootRun remains supported; Docker is required
only for the container/fault-injection path.

The runner uses [Google Identity Toolkit](https://firebase.google.com/docs/reference/rest/auth)
with FIREBASE_WEB_API_KEY/TEST_EMAIL/TEST_PASSWORD, checks returned UID against
FIREBASE_TEST_UID, keeps tokens/passwords in memory. Alternatively use Firebase
client SDK outside source. Never turn chat credentials into source literals.
Manual check with token already in env; no transcript/debug output:

```powershell
$headers = @{ Authorization = 'Bearer ' + $env:FIREBASE_ID_TOKEN
              'Idempotency-Key' = [Guid]::NewGuid().ToString()
              'X-Correlation-Id' = 'operator-live-check' }
# Trust public dev CA in the operator client; never SkipCertificateCheck / -k.
$created = Invoke-WebRequest -Method Post -Uri ($env:GATEWAY_URL + '/api/v1/users/me/bootstrap') -Headers $headers -ContentType 'application/json' -Body '{}'
$profile = Invoke-WebRequest -Method Get -Uri ($env:GATEWAY_URL + '/api/v1/users/me') -Headers $headers
$profile.StatusCode
$profile.Headers.ETag
Remove-Variable headers,created,profile
```

Bootstrap201 defaults HOUSEHOLD_USER; existing200 preserves role. Operator mapping
**instead of bootstrap**: set MAP_FIREBASE_UID, explicit MAP_ROLE and
MAP_DB_URL/USERNAME/PASSWORD (migrator), then run
`gradlew.bat :services:identity-service:mapFirebaseUser`. Task verifies actual UID
with Firebase, requires active user and hc_dev_identity/hc_test_<run>_identity,
inserts profile/mapping/role atomically; duplicates fail/rollback, no role upsert.
Mapping code is operator/test-only, excluded from bootJar. No password storage.

Live suite requires FIREBASE_LIVE_TEST=true and complete env. Missing env means
SKIP/BLOCKED; invalid supplied configuration/assertions mean FAIL. It is not part
of test/check/integrationTest. FIREBASE_TEST_MUTATIONS=true enables dedicated-user
revoke/disable; disable and app status restore in finally. Revoke is irreversible
for existing sessions. Only isolated test DB may be mutated. Failed cleanup must
be resolved by operator before rerun. Optional FIREBASE_EXPIRED_ID_TOKEN and
FIREBASE_WRONG_PROJECT_ID_TOKEN must be Google-issued; missing cases stay BLOCKED.

FIREBASE_NETWORK_FAULT_TEST=true requires Docker CLI and LIVE_COMPOSE_PROJECT
(default example homecare-foundation). Run serially: selected container's egress
network is disconnected while internal mTLS/DB remains, expect503, reconnect in
finally. If interrupted, operator reconnects the network before continuing. No
Google fixture; connection failure is not counted as503. GatewayTransportIT
separately proves502/503/504 and single POST using local TLS wire faults.
Image/Compose build is not authenticated live evidence. Audit§12 records results;
cert rotation, authenticated probes, SLO and frontend E2E remain open.

### Flow tạo key/env và chạy project

Cấu hình Spring của **tám service** hiện ở `src/main/resources/application.yml`.
`.env` cung cấp giá trị môi trường, không thay thế YAML; Spring không tự đọc mọi
file `.env`. Docker nạp env riêng theo `env_file`; PowerShell dùng helper dưới đây.
Giữ YAML trong Git; credential và env thật ở thư mục operator ngoài checkout.

**1. Chuẩn bị Firebase trong project dev được phép dùng.** Trong Firebase Console,
chọn đúng project; lấy Project ID từ Project settings. Bật Authentication →
Sign-in method → Email/Password, tạo user test riêng trong Users và lấy UID.
Không suy role từ email; role mặc định sau bootstrap vẫn HOUSEHOLD_USER.
[Hướng dẫn email/password chính thức](https://firebase.google.com/docs/auth/web/password-auth).

Có hai loại key khác nhau:

| Thành phần | Lấy/tạo ở đâu | Đặt vào đâu |
|---|---|---|
| Service-account JSON / ADC | Project settings → Service accounts; dùng credential đúng project đã có. Nếu chưa có và được cấp quyền, operator tạo/download private key theo Firebase Admin setup | File ngoài repo; `GOOGLE_APPLICATION_CREDENTIALS` trong compose.env và live.env là **đường dẫn**, không phải nội dung JSON |
| Web API key | Firebase Web app config `apiKey`, hoặc Google Cloud → APIs & Services → Credentials; chọn đúng project và restrictions cho client test | Chỉ `FIREBASE_WEB_API_KEY` của live.env, không cần ở Gateway/Identity |
| UID/email/password user test | Firebase Authentication → Users; password do operator quản lý, không tự đọc từ service-account JSON | `FIREBASE_TEST_UID/EMAIL/PASSWORD` trong live.env |
| Workload CA/cert/keystore | Script `new-dev-pki.ps1`, độc lập với Firebase | File ngoài repo; các biến TLS theo bước3 |
| DB/broker passwords | `dev.ps1 init` sinh ngẫu nhiên một lần, không ghi đè `.env` đã có | Foundation `.env`; copy đúng runtime DB password vào identity.env/live.env bằng editor riêng |

Nguồn: [Firebase Admin ADC](https://firebase.google.com/docs/admin/setup),
[Firebase API keys](https://firebase.google.com/docs/projects/api-keys).
Không tạo thêm Google project hoặc cấp quyền Admin cho runtime chỉ để vượt lỗi.
Nếu revoke/disable cần quyền cao hơn, dùng FIREBASE_OPERATOR_CREDENTIALS riêng.

**2. Chuẩn bị bốn file env ngoài repo.** Chọn thư mục operator đã bảo vệ ACL;
set `$env:HOMECARE_OPERATOR_DIRECTORY` trỏ tới đó. Copy template một lần, không
ghi đè file thật đã cấu hình:

```powershell
# CWD: C:\Projects\homecare\homecare-api
Copy-Item services/identity-service/.env.example (Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'identity.env')
Copy-Item services/gateway-service/.env.example (Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'gateway.env')
Copy-Item scripts/.env.compose.example (Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'compose.env')
Copy-Item scripts/.env.live.example (Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'live.env')
```

Điền bằng editor local, không gửi nội dung lên chat. Format helper là NAME=value,
không quote bao ngoài, không `export`, không command substitution. Dùng forward
slashes cho Windows paths trong env Compose; không chạy command in toàn bộ env.

**3. Sinh TLS dev.** Trong PowerShell, tạo password ngẫu nhiên không in ra console:

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$env:DEV_PKI_PASSWORD = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$env:DEV_PKI_DIRECTORY = Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'pki'
.\scripts\new-dev-pki.ps1 -OutputDirectory $env:DEV_PKI_DIRECTORY
```

Lưu password vào secret store hoặc editor env riêng, không in ra log; script không
lưu password hộ. `pki` phải chưa tồn tại. Gán mapping sau, giữ cert private ngoài repo:

| Biến | Giá trị / consumer |
|---|---|
| IDENTITY_TLS_KEYSTORE | Absolute path `pki/identity.p12`, compose.env + live.env |
| GATEWAY_TLS_KEYSTORE | Absolute path `pki/gateway.p12`, compose.env + live.env |
| TLS_TRUSTSTORE | Absolute path `pki/trust.p12`, compose.env + live.env |
| TLS_KEYSTORE_PASSWORD / TLS_TRUSTSTORE_PASSWORD | DEV_PKI_PASSWORD vừa tạo, trong từng identity.env/gateway.env |
| IDENTITY_TLS_KEYSTORE_PASSWORD / GATEWAY_TLS_KEYSTORE_PASSWORD / TLS_TRUSTSTORE_PASSWORD | Cùng password tương ứng, live.env |
| ROGUE_TLS_KEYSTORE / ROGUE_TLS_KEYSTORE_PASSWORD | `pki/rogue.p12` + password, chỉ live.env để test CN lạ |
| GATEWAY_ENV_FILE / IDENTITY_ENV_FILE | Absolute path gateway.env/identity.env trong compose.env |

Không mount `ca.p12` vào app. Compose tự đổi đường dẫn container thành
`/run/secrets/...`; không dùng đường dẫn Windows làm TLS_KEYSTORE bên trong container.

**4. Chuẩn bị DB/broker.** Chạy Docker Desktop Linux engine. Không reset volume:

```powershell
if (!(Test-Path .env)) { .\scripts\dev.ps1 init }
.\scripts\dev.ps1 up
# Chỉ provision lần đầu; nếu prefix đã tồn tại thì bỏ qua provision, không xóa DB.
.\scripts\dev.ps1 provision -DatabasePrefix hc_test_live
.\scripts\dev.ps1 migrate -DatabasePrefix hc_test_live
```

Identity: DATABASE_URL=`jdbc:postgresql://postgres:5432/hc_test_live_identity`,
DB_USERNAME=`hc_test_live_identity_app`, DB_PASSWORD khớp IDENTITY_DB_PASSWORD
đã provision. Live runner: LIVE_DB_URL dùng localhost:55432 (hoặc POSTGRES_PORT
thực tế), LIVE_DB_USERNAME/PASSWORD cùng runtime account của DB test này.
Gateway không có DB env. RABBITMQ_URL dùng cho các service có AMQP; Identity và
Gateway không cần. Không dùng migrator account để chạy app.

**5. Nạp env và chạy Identity trước, Gateway sau.** FIREBASE_PROJECT_ID phải giống
nhau ở hai service và live.env, khớp ADC. Gateway IDENTITY_URL dùng
`https://identity:8443`, live.env IDENTITY_URL dùng `https://localhost:8443`.
GATEWAY_URL=`https://localhost:8080`. CORS_ORIGINS khớp origin web thực tế.

```powershell
$env:COMPOSE_ENV_FILE = Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'compose.env'
.\scripts\Import-LocalEnvironment.ps1 -Path $env:COMPOSE_ENV_FILE
.\scripts\Import-LocalEnvironment.ps1 -Path (Join-Path $env:HOMECARE_OPERATOR_DIRECTORY 'live.env')
.\scripts\live-preflight.ps1
.\gradlew.bat :services:identity-service:bootJar :services:gateway-service:bootJar
# Tiếp tục đúng chuỗi compose config/build/up identity/up gateway ở phần Phase3b phía trên.
```

Lưu ý shell env ưu tiên hơn `--env-file`: host live.env IDENTITY_URL khác target
trong gateway.env, nhưng Compose chỉ lấy target qua `env_file` của Gateway và
không interpolate IDENTITY_URL trong compose YAML. Không thay bằng host localhost.
Không mở health guest để kiểm startup. Dùng TLS client certificate/truststore
và request authenticated; không bỏ kiểm tra certificate.

**6. Nghiệm thu.** Đặt FIREBASE_LIVE_TEST=true trong live.env rồi import lại và chạy
`gradlew.bat :services:identity-service:liveFirebaseTest`. Runner login Google,
bootstrap lần đầu rồi GET/PATCH/resolve. Các opt-in mutation/network cases và
token expired/wrong-project theo phần Phase3b; thiếu điều kiện vẫn BLOCKED.
CI/local không cần Google dùng `scripts/dev.ps1 test -DatabasePrefix hc_test_p3a`
với DB đã provision tương ứng. Không xem fixture PASS là Google live PASS.

**Tổ chức code sau refactor 2026-11-03:** GatewayRoutesConfiguration chỉ khai báo
route; GatewayHttpClientConfiguration quản lý transport/mTLS/timeouts;
GatewayRoutePolicy cung cấp exact method/path policy cho filter và security;
GatewayBoundaryFilter xử lý HTTP boundary; GatewaySecurityConfiguration cấu hình
security chain. IdentitySecurityConfiguration nằm trong package security;
IdempotencyCleanupJob diễn đạt đúng tác vụ dọn dữ liệu. File/class cũ đã được
thay thế, không giữ hai bản cùng chức năng; không tạo shared business JAR hoặc
interface tổng quát chỉ để tăng số lớp. Contract và transaction semantics giữ nguyên.

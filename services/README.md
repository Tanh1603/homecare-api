# HomeCare Backend Services — Construction, Configuration & Run Guide

Each directory under `homecare-api/services/` is an independent Spring Boot 4.1.2 (Java 25) microservice module within the `homecare-api` multi-project Gradle build. All services share common web/security contracts via `:libs:common-web` and gRPC protobuf stubs via `:libs:grpc-contracts`.

---

## 1. Service Map & Ports

| Service | Gradle Module | HTTP Port | gRPC Port | Database (`PostgreSQL 16`) | CAP & Consistency Role | Env Example |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| **Gateway Service** | `:services:gateway-service` | `8080` | — | Stateless + Redis `:6379` | Edge Router, JWT Auth, Token Blacklist, Guest GET Allowlist | [`services/gateway-service/.env.example`](./gateway-service/.env.example) |
| **Identity Service** | `:services:identity-service` | `8081` | — | `identity_db` + Redis `:6379` | Keycloak OIDC Auth Proxy + Local `user_profiles` (`keycloak_user_id` = `jwt.sub`) | [`services/identity-service/.env.example`](./identity-service/.env.example) |
| **Household Service** | `:services:household-service` | `8082` | — | `household_db` | Household, Device (`@Version` ETag), Warranty, Maintenance Reminder + `household_outbox_events` | [`services/household-service/.env.example`](./household-service/.env.example) |
| **Provider Service** | `:services:provider-service` | `8083` | `9083` | `provider_db` | **CP Core:** Catalog, Technician Schedule, AI Auto-Dispatch gRPC (`@Version` + `intentId` Ledger) | [`services/provider-service/.env.example`](./provider-service/.env.example) |
| **Booking Service** | `:services:booking-service` | `8084` | Client | `booking_db` | **AP + Saga Orchestrator:** `booking_allocation_saga` + `booking_outbox_events` + Work Updates | [`services/booking-service/.env.example`](./booking-service/.env.example) |
| **Commerce Service** | `:services:commerce-service` | `8085` | — | `commerce_db` | **CP Inventory + AP Payment:** `inventory_reservation` (15m TTL), VNPAY Sandbox `HMAC-SHA512`, `commerce_outbox_events` | [`services/commerce-service/.env.example`](./commerce-service/.env.example) |
| **Review Service** | `:services:review-service` | `8086` | — | `review_db` | Verified Service & Product Reviews (checks Booking `COMPLETED` / OrderLine `DELIVERED`) | [`services/review-service/.env.example`](./review-service/.env.example) |
| **Notification Service** | `:services:notification-service` | `8087` | — | `notification_db` | **AP Consumer:** RabbitMQ `homecare.domain.events` + `processed_events` deduplication + Inbox | [`services/notification-service/.env.example`](./notification-service/.env.example) |

---

## 2. Environment Configuration (`.env.example` → `.env`)

1. **Root Infrastructure & Full Stack Compose (`homecare-api/.env`):**
   ```powershell
   Copy-Item homecare-api/.env.example homecare-api/.env
   ```
2. **Per-Service Local Standalone Run (`homecare-api/services/<service-name>/.env`):**
   Each service includes a dedicated `.env.example` template with localhost defaults (`localhost:5432`, `localhost:6379`, `localhost:5672`, `localhost:8180` for Keycloak):
   ```powershell
   Copy-Item homecare-api/services/household-service/.env.example homecare-api/services/household-service/.env
   Copy-Item homecare-api/services/provider-service/.env.example homecare-api/services/provider-service/.env
   Copy-Item homecare-api/services/booking-service/.env.example homecare-api/services/booking-service/.env
   Copy-Item homecare-api/services/commerce-service/.env.example homecare-api/services/commerce-service/.env
   Copy-Item homecare-api/services/review-service/.env.example homecare-api/services/review-service/.env
   Copy-Item homecare-api/services/notification-service/.env.example homecare-api/services/notification-service/.env
   ```
   > **Note:** All domain services provide sensible local fallback defaults in `src/main/resources/application.properties` so you can also run them directly with `./gradlew :services:<service-name>:bootRun` once infrastructure containers are up.

---

## 3. Construction & Running Instructions

### Step 1: Start Local Infrastructure (PostgreSQL 7 DBs, Redis, RabbitMQ, Keycloak)
From `homecare-api/`:
```powershell
docker compose -f compose.infra.yml --env-file .env up -d
```
- **PostgreSQL 16 (`:5432`)** automatically initializes 7 databases via `docker/postgres/init-multiple-dbs.sh`: `identity_db`, `household_db`, `provider_db`, `booking_db`, `commerce_db`, `review_db`, `notification_db`.
- **Keycloak (`:8180`)** automatically imports `docker/keycloak/homecare-realm.json` with realm roles (`CUSTOMER`, `TECHNICIAN`, `ADMIN`, `SUPPORT`) and demo accounts (`demo-customer`, `demo-technician`, `demo-admin`).
- **RabbitMQ (`:5672`, UI `:15672`)** and **Redis (`:6379`)** start with healthchecks.

### Step 2: Build Shared Libraries & Services
From `homecare-api/` (or repository root using `-p homecare-api`):
```powershell
# Compile and run unit tests across all shared libraries and microservices
.\gradlew.bat test

# Build executable Spring Boot JARs for all 8 microservices
.\gradlew.bat bootJar
```

### Step 3: Run Services Locally or via Docker Compose
- **Option A — Run Full Application Stack via Docker Compose:**
  ```powershell
  docker compose -f compose.infra.yml -f compose.app.yml --env-file .env up --build -d
  ```
- **Option B — Run Individual Services via Gradle (`bootRun`):**
  ```powershell
  .\gradlew.bat :services:gateway-service:bootRun
  .\gradlew.bat :services:identity-service:bootRun
  .\gradlew.bat :services:household-service:bootRun
  .\gradlew.bat :services:provider-service:bootRun
  .\gradlew.bat :services:booking-service:bootRun
  .\gradlew.bat :services:commerce-service:bootRun
  .\gradlew.bat :services:review-service:bootRun
  .\gradlew.bat :services:notification-service:bootRun
  ```

---

## 4. Key Architectural & Security Conventions

1. **No Cross-Service Foreign Keys (`jwt.sub` Direct Storage):**
   All user reference columns across services (`creator_user_id` / `owner_profile_id`, `requester_profile_id`, `buyer_profile_id`, `author_profile_id`, `recipient_id`, `profile_id`) store the Keycloak Subject ID (`jwt.getSubject()`) directly.
2. **Unified Error Envelope (`:libs:common-web`):**
   Every service extends `BaseApiExceptionHandler`, `ServletApiResponseFactory`, and `ServletCorrelationIdFilter` from `:libs:common-web`, returning `{ status, errorCode, message, data: null, errors, meta: { timestamp, traceId } }`.
3. **Internal Workload Protection (`/internal/v1/**` & gRPC `:9083`):**
   - Blocked at `gateway-service` (`/internal/** -> denyAll()`).
   - Verified inside downstream services via `X-Internal-Workload-Token` header / gRPC metadata (`INTERNAL_WORKLOAD_TOKEN`).

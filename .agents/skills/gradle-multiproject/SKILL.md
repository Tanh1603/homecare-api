---
name: gradle-multiproject
description: Navigates, changes, and verifies the HomeCare backend Gradle multi-project build and service-specific dependencies or tasks.
---

# Gradle multi-project backend

## Purpose

Make scoped build/dependency changes in the parent `homecare-api/` directory without confusing the monorepo root with the Gradle root.

## When to use

- A task changes Gradle configuration, module inclusion, dependency scope, Java toolchain, build tasks, or service packaging.

## When not to use

- Pure business code changes that need no build configuration; use `spring-boot` instead.

## Prerequisites

From the `homecare-api/` build root, read `settings.gradle`, root `build.gradle`, `gradle.properties`, the service's `build.gradle`, and `services/README.md`. Current service projects are `:services:identity-service`, `:services:household-service`, `:services:provider-service`, `:services:booking-service`, `:services:commerce-service`, `:services:review-service`, and `:services:notification-service`.

## Core principles

- Run the wrapper from the parent `homecare-api/`; do not assume a root Gradle build exists.
- Keep shared plugin/toolchain/dependency policy centralized only when it truly applies to every service; keep service-only libraries in that service module.
- Preserve isolated module outputs and avoid committing generated `build/` files.
- Inspect the dependency tree/version management before adding or upgrading a library.
- Do not add a new service/module without approved boundary and ownership.

## Implementation guidelines

1. Use `gradlew.bat` on Windows, `./gradlew` elsewhere (from the parent `homecare-api/`).
2. Target a narrow task, e.g. `:services:booking-service:test` or `:services:booking-service:bootJar`.
3. Use `dependencies` or `dependencyInsight` when resolving an actual version/conflict question.
4. Explain why a dependency belongs in the root build or one module; avoid unrelated upgrades.

## Common mistakes

- Running commands from repository root or mobile Gradle root.
- Adding database, RabbitMQ, Firebase, or test libraries to all services without need.
- Treating a dependency declaration as proof the integration is implemented.
- Checking in generated output or wrapper changes unrelated to the task.

## Validation checklist

- [ ] Correct Gradle root and real module path.
- [ ] Dependency scope matches module ownership.
- [ ] Wrapper task succeeds or its blocker is reported.
- [ ] No generated artifacts or unrelated version changes are included.

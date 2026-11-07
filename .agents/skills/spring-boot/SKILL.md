---
name: spring-boot
description: Implements or reviews Java Spring Boot changes within an existing HomeCare backend service using the repository's current framework baseline.
---

# Spring Boot in HomeCare

## Purpose

Apply Spring Boot conventions without assuming that every Spring component in the proposed architecture is already installed or configured.

## When to use

- A task changes an existing service's Spring application, dependency injection, configuration, validation, security boundary, persistence adapter, or HTTP adapter.

## When not to use

- Architecture-only decisions, frontend/mobile changes, or a request to add a new framework/infrastructure component. For new infrastructure, start with the architect profile and relevant ADR.

## Prerequisites

Read the target module and `../build.gradle`. Current observed baseline: Java 21; Spring Boot 4.1.2; inherited Spring MVC, validation, Spring Security, Actuator, and Boot test starter. Confirm the exact module dependencies before using an API.

## Core principles

- Follow existing code and the version-matched documentation available in the local dependency when making framework-specific changes.
- Keep controllers/adapters thin; keep domain/application behavior out of HTTP and repository mechanics.
- Use constructor injection, explicit DTO mapping, and typed configuration consistent with neighboring code.
- Keep authentication and authorization distinct; resource services must enforce their own resource/domain permissions.
- Keep exceptions mapped consistently and avoid exposing internal details.
- Do not add Spring Cloud, gRPC, Redis, Flyway, or other dependencies merely because they are common in Spring systems.

## Implementation guidelines

1. Identify the owning service and contract/AC.
2. Inspect package layout, existing configuration, dependency versions, and test patterns.
3. Make the smallest vertical change; avoid creating generic frameworks for one use.
4. Add or adapt tests from the approved behavior.
5. Run the narrow module test/build task if requested or required by the task; state exactly what ran.

## Common mistakes

- Coding against a familiar Spring version rather than this repo's Spring Boot 4.1.2 baseline.
- Treating Actuator presence as a configured observability platform.
- Assuming Firebase verification, Gateway routes, or Spring Security rules are wired because dependencies/docs mention them.
- Returning entities, trusting caller identity fields, or putting business rules in controllers.

## Validation checklist

- [ ] Target module owns the behavior and has the needed dependency.
- [ ] Contract and security semantics are preserved.
- [ ] No unsupported infrastructure was introduced.
- [ ] Errors and DTO boundaries are safe and consistent.
- [ ] Relevant tests/build evidence is reported accurately.

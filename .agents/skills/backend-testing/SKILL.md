---
name: backend-testing
description: Plans and implements focused JUnit tests for HomeCare Spring services, including application, API, persistence, messaging, security, and failure behavior.
---

# Backend testing

## Purpose

Turn approved requirements/contracts into deterministic evidence at the appropriate test layer.

## When to use

- New behavior, bug fix, contract/security change, persistence/messaging integration, or test coverage review.

## When not to use

- Product policy decisions not yet approved; first resolve or explicitly preserve the TBD.

## Prerequisites

Inspect existing tests, Gradle dependencies, and module code. JUnit Platform and Spring Boot's test starter are configured centrally. Mockito/Testcontainers are not evidenced in the audited build; inspect before use.

## Core principles

- Derive expected behavior from AC/use case/contract, not the implementation under test.
- Unit tests cover domain/application decisions; integration tests cover framework, persistence, HTTP, security, or broker boundaries that matter.
- Test observable behavior, authorization failures, duplicate/retry semantics, and regressions; avoid brittle implementation-detail assertions.
- Keep tests isolated, deterministic, and free of real secrets/external accounts.
- Testcontainers is a possible tool only after checking dependency, runtime availability, and CI support; it is not a project standard yet.

## Implementation guidelines

1. List behavior and boundary risks before writing tests.
2. Choose the smallest useful test layer and existing fixture style.
3. Include relevant happy path, invalid input, domain rejection, security, and failure cases.
4. Run the narrow Gradle test task from this skill set's parent (`homecare-api/`) when testing is requested or required.
5. Report skipped tests and environmental blockers plainly.

## Common mistakes

- Mocking every collaborator in a test meant to verify a framework boundary.
- Testing only happy paths for authorization or asynchronous delivery.
- Treating test coverage as proof that the API/architecture contract is correct.
- Silently converting TBD behavior into assertions.

## Validation checklist

- [ ] Assertions trace to approved expected behavior.
- [ ] Tests are deterministic and isolated.
- [ ] Boundary failures and access control are covered when relevant.
- [ ] Exact command and result are reported.

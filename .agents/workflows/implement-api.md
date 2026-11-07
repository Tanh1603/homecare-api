# Workflow: implement or revise an API operation

## Entry criteria

Use when a requested task names an operation or an approved requirement/use case clearly implies its contract. If the operation has no approved contract, first use `design-change.md` and keep the proposal distinct from implementation.

## Steps

1. Load `agents/api-engineer.md`, `skills/rest-api-contracts/SKILL.md`, `skills/spring-boot/SKILL.md`, and project/API rules.
2. Read the use case, AC, API conventions, service catalog/contract, relevant ADR, and target service implementation.
3. Record method, path, request/response, structural/business validation, success/error statuses, auth requirement, resource ownership, side effects, idempotency, and pagination/filter behavior as applicable.
4. Verify `/api/v1`, JSON naming/date conventions, DTO/entity separation, error envelope, guest allowlist, `X-Correlation-Id`, and compatibility expectations.
5. Implement request DTO → validation → authorization → use case → response DTO/error mapping, keeping domain behavior out of controller and structural validation.
6. Add tests for success, malformed request, domain conflict, unauthenticated/unauthorized/resource ownership, and compatibility where relevant.
7. Compare actual implementation with the operation contract using `contract-audit.md`.

## Stop conditions

- Endpoint, field, status transition, auth policy, or side effect is not traceable and is necessary to implement correctly.
- Contract and source code disagree; preserve the mismatch and report it.

## Exit criteria and output

Operation matches its approved contract, relevant tests/checks are reported, and any unresolved status mapping/security/idempotency issue is explicit.

---
name: rest-api-contracts
description: Designs, implements, or audits HomeCare REST operations against the approved API conventions and traceable endpoint contract.
---

# REST API contracts

## Purpose

Apply common REST conventions while ensuring each endpoint has a real requirement/use-case contract.

## When to use

- API design/implementation, DTO validation, HTTP status/error mapping, API compatibility, or contract audit.

## When not to use

- Internal event protocols or architecture-only exploration.

## Prerequisites

Read `../../../../docs/03-api/api-conventions.md`, the relevant API catalog/service contract, SRS/use case/AC, and `../../../services/README.md`.

## Core principles

- Approved common baseline includes `/api/v1`, REST/JSON, lowerCamelCase JSON fields, request/response DTOs, common error envelope, guest discovery/catalog GET allowlist, page-based pagination (`size=20`, max `100`), and `X-Correlation-Id`.
- These conventions do not define a business endpoint, request schema, status transition, or role matrix.
- Structural validation, business validation, and authorization are separate concerns.
- Authorization is enforced by the resource-owning service; Firebase token verification at Gateway is an approved direction, with caller-context trust still TBD.
- Preserve backward compatibility and do not claim idempotency unless an operation policy is specified.

## Implementation guidelines

1. Map operation to requirement/use case/AC and owner.
2. Confirm method, path, request/response schema, statuses, error codes, auth policy, and side effects.
3. Use DTOs and schema-based validation; enforce domain rules in domain/application code.
4. Add tests for success, malformed input, business rejection, unauthenticated/unauthorized access, and compatibility as relevant.
5. Audit actual operation against its contract; report missing/extra/mismatched behavior.

## Common mistakes

- Creating convenient endpoints absent from approved contract.
- Exposing JPA entities or server-controlled status/identity fields.
- Treating all operations as authenticated or all discovery reads as public.
- Returning 200 for asynchronous acceptance or leaking exception detail.

## Validation checklist

- [ ] Traceability and owning service established.
- [ ] DTO, validation, statuses, errors, and authorization match contract.
- [ ] Guest scope and side effects are explicit.
- [ ] Compatibility and tests considered.

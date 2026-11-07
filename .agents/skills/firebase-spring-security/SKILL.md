---
name: firebase-spring-security
description: Implements or reviews Firebase ID-token authentication boundaries and Spring Security authorization for HomeCare APIs.
---

# Firebase Authentication and Spring Security

## Purpose

Preserve the separation between authentication by Firebase, Gateway token verification, and authorization by the resource-owning backend service.

## When to use

- Firebase identity integration, principal construction, Spring Security configuration, role checks, ownership/assignment authorization, or security review.

## When not to use

- Product authorization policy design without approved roles/actions, or frontend-only Firebase SDK work.

## Prerequisites

Read ADR-004, ADR-005, API conventions, identity service docs, authentication sequence, and relevant security open questions. Verify actual code: Firebase Admin dependency is observed in Identity, but Gateway verification, service security, role lifecycle, and trust propagation are not thereby proven complete.

## Core principles

- Valid identity is not application permission.
- Gateway verifies Firebase ID token as approved direction; resource services enforce RBAC plus resource/domain authorization.
- Do not trust user ID, role, or ownership claims supplied by client fields or untrusted headers.
- Keep authentication (401) distinct from authorization (403); use concealment only when a policy explicitly requires it.
- Verify issuer, audience/project, signature, expiry, and revocation behavior according to the agreed Firebase integration contract; do not invent project/provider configuration.
- Protect caller context between Gateway and downstream services; the trust mechanism remains TBD until documented.

## Implementation guidelines

1. Map protected operation to a documented actor/action/resource policy.
2. Trace token verification and principal creation across the actual boundary.
3. Authorize operation and resource ownership at the owning service.
4. Minimize claims and identity data; keep token and personal information out of logs.
5. Test missing, invalid, expired token; insufficient role; wrong resource owner; and allowed access.

## Common mistakes

- Treating Firebase UID as a role or authorization decision.
- Accepting identity headers from the public client.
- Securing only at Gateway while downstream service can be reached without trusted context.
- Inventing role names/claims or claiming verified behavior without implementation evidence.

## Validation checklist

- [ ] Authentication source and boundary are explicit.
- [ ] Caller identity cannot be spoofed by request data.
- [ ] Role and resource ownership checks happen in the owning service.
- [ ] Unauthorized access does not leak sensitive resource data.
- [ ] Negative-path tests and config evidence exist.

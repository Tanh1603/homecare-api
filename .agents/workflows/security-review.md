# Workflow: review or implement security behavior

## Entry criteria

Use for authentication, authorization, Gateway trust, resource ownership, Firebase integration, sensitive data, or a security-sensitive API change.

## Steps

1. Load `agents/security-reviewer.md`, `skills/firebase-spring-security/SKILL.md`, `rules/security-and-data.md`, ADR-004/005, and relevant API contract.
2. Identify public boundary, token verifier, principal creation, downstream trust mechanism, owning service, operation, role/action/resource, and expected data exposure.
3. Inspect real filters/configuration/tests at every boundary; separate implemented controls from approved directions and scaffold dependencies.
4. Trace untrusted inputs including body/path/query/header, forwarded identity, caller-controlled resource IDs, logs, and error output.
5. Check authentication vs authorization, resource ownership/assignment, IDOR, guest allowlist, least privilege, token validation, secret handling, and failure defaults.
6. Test missing/invalid/expired token, wrong role, wrong owner, tampered identity header, and safe failure response as relevant.
7. If policy or trust mechanism is `TBD`, report concrete risk and decision needed; do not invent roles/claims or mark implementation complete.

## Stop conditions

Any unresolved identity/trust or authorization policy necessary to safely expose the operation. Continue with independent audit/remediation that does not depend on it.

## Exit criteria and output

Return prioritized findings with evidence and exploit path, then scoped remediation guidance or implementation evidence. State what was actually tested.

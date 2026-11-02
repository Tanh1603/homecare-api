# Security and data rules

- Authenticate the caller at the documented boundary, then authorize each operation and resource at the service that owns it.
- Derive caller identity from a verified principal, never from a request body, query parameter, path value, or untrusted identity header.
- Check ownership/assignment for resource access to prevent IDOR. Avoid leaking existence or sensitive fields through error responses.
- Keep secrets out of source, logs, examples, and test fixtures. Redact tokens and sensitive personal data.
- A service owns its persistence. No cross-service SQL, JPA relationship, foreign key, or shared business entity. Cross-service identifiers are opaque references unless a contract says otherwise.
- Treat schema and migration choices as contract changes. Existing docs describe Hibernate `validate`; Flyway has not been selected in audited configuration. Do not add migrations or change schema absent a requested, traceable data change and an agreed migration approach.
- Do not mark a control as implemented because an ADR approves its direction; inspect the actual configuration and tests.

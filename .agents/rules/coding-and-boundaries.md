# Backend coding and boundary rules

- Keep HTTP adaptation in controllers, orchestration in application/use-case services, business invariants in the domain, and storage mechanics in repositories/adapters. Fit the pattern to the existing module; do not create layers solely to satisfy a diagram.
- Do not expose JPA entities as API representations. Use request/response DTOs and explicit mapping where needed.
- Validate structure at the boundary and enforce authorization and domain invariants on the server. Do not treat DTO validation as domain validation.
- Do not catch and ignore exceptions, return success after failed persistence, or expose stack traces, SQL, credentials, tokens, or internal exception detail to clients.
- Keep transaction scope explicit and local to the owning service. Do not imply a distributed transaction across services.
- Avoid speculative abstractions, generic frameworks, and dependencies without a requirement and an approved architecture fit.
- Match the existing Java/Gradle conventions and current Spring Boot version. Read the module's sources and configuration before introducing a pattern.
- Do not use project-wide component assumptions as evidence: check whether the class/configuration actually exists in this checkout.

# Workflow: implement within an existing service

## Entry criteria

Use for a requested feature/bug fix inside a named existing backend service, with behavior traceable to a requirement/AC or approved contract.

## Steps

1. Load project baseline, `agents/backend-developer.md`, `skills/spring-boot/SKILL.md`, `skills/gradle-multiproject/SKILL.md`, and `skills/ddd-traceability/SKILL.md` as needed.
2. Confirm target service exists in `../settings.gradle`; inspect its sources, build file, config, neighboring tests, and service README.
3. Trace requirement → use case/AC → owning context/aggregate → service → contract/event → persistence → authorization.
4. List behavior and open questions. Do not implement unspecified lifecycle states, fields, roles, side effects, or cross-service calls.
5. Make a small implementation plan and implement one vertical slice in the owning module. Keep controller, application, domain, and persistence concerns appropriately separated without forcing new abstractions.
6. Keep transactions local; if a cross-service effect is required, use an approved contract. For messaging, switch to `rabbitmq-event.md`.
7. Add or update focused tests based on approved behavior; switch to `write-tests.md` when test work is the main task.
8. Inspect diff for scope, secrets, generated output, dependency additions, and documentation impact.
9. Run the relevant module build/test task only when requested or required by the task instructions; report exact evidence.

## Stop conditions

- No agreed API/event/data contract for required behavior.
- Ownership boundary or caller-context trust is unresolved and security-critical.
- New infrastructure or a schema decision is needed but unapproved.

## Exit criteria and output

Behavior is implemented only within specified scope; tests/checks and limitations are accurately reported; any contract/architecture gap is listed without silent resolution.

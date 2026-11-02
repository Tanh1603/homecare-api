# Workflow: design a backend change

## Entry criteria

Use before implementing a feature that changes domain behavior, service boundaries, persistence ownership, API/event contracts, authentication, or infrastructure. Input should include a task/request and target area; if either is missing, audit independently identifiable scope and list what remains unclear.

## Steps

1. Load `rules/project-baseline.md`, `agents/architect.md`, `skills/ddd-traceability/SKILL.md`, and the relevant role/skill.
2. Find the source requirement, use case, acceptance criteria, traceability row, DDD context/aggregate, service contract, ADR, and open question. Do not assume paths; discover them.
3. Inspect the actual service/module/configuration and distinguish code evidence from documentation intent.
4. Write a trace: requirement → use case/AC → context/aggregate → owning service → API/event → data → security → tests/operations.
5. Classify each material decision `OBSERVED`, `SOURCE`, `APPROVED`, `PROPOSED`, `TBD`, or `CONFLICT`.
6. Identify dependencies, data ownership, sync/async calls, compatibility, failure modes, migration/rollout, and security consequences.
7. If architecture or contract must change, present a bounded proposal and alternatives. Do not silently implement the proposal as approved architecture.

## Stop conditions

- A missing business rule prevents a correct implementation: preserve it as `TBD`; complete unrelated work and return the blocking decision with evidence.
- A source conflict affects a choice: report both sources and defer only that decision.
- An approved contract/ADR must change: stop before dependent code until the change is authorized or the task already explicitly authorizes that design change.

## Exit criteria and output

Provide the trace, current status/evidence, affected files/services/contracts, unresolved decisions, and a scoped implementation plan. Update only documents that are in scope.

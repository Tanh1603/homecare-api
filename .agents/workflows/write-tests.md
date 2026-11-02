# Workflow: write backend tests

## Entry criteria

Use when tests are the requested task or required to validate a scoped backend change. The developer instruction for the task determines whether tests should be run.

## Steps

1. Load `agents/test-engineer.md` and `skills/backend-testing/SKILL.md`; inspect the target module's current tests and dependencies.
2. Translate approved AC/contract behavior into observable cases; list unresolved expected behavior separately.
3. Select unit, MVC/API, persistence, security, messaging, or module integration test based on the boundary being verified.
4. Use deterministic test data and safe fakes. Check support before proposing Testcontainers or external service orchestration.
5. Cover relevant success, validation, authorization, domain conflict, duplicate/retry, and failure cases without asserting unresolved policy.
6. Run the narrow wrapper task from the `homecare-api/` directory if requested/required; expand to broader checks only when justified.
7. Report exact task, pass/fail, skipped coverage, and environment constraints.

## Exit criteria

Tests are focused, deterministic, traceable, and provide meaningful evidence; no unexecuted test is reported as passed.

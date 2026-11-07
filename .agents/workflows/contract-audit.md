# Workflow: audit implementation against contract

## Entry criteria

Use for a read-only or requested audit of an API, event, security, data, or implemented service behavior against an approved source.

## Steps

1. Identify the canonical contract (requirement/use case/AC/API convention/service contract/ADR) and its status. Do not use code as the contract.
2. Inspect actual implementation, configuration, persistence, tests, and module build. Keep code evidence separate from README/ADR statements.
3. Compare applicable elements:
   - HTTP: method, path, schema, validation, status, errors, auth, idempotency, compatibility.
   - Events: producer/consumer, envelope/version, routing, outbox/inbox, dedupe, retry/DLX, payload privacy.
   - Data: owner, schema, constraints, migration, transaction, cross-service access.
   - Security: token verification, caller context, role/resource check, IDOR, data/log exposure.
4. Classify each item `COMPLIANT`, `MISMATCH`, `MISSING`, `EXTRA`, `CONFLICT`, or `TBD`.
5. Rank actionable findings by user/security/data impact and cite exact file/line when possible.
6. Do not modify the contract or implementation during an audit unless the task also asks for remediation.

## Exit criteria and output

Provide summary, findings with evidence and status, unverified areas, and remediation options. A dependency or scaffold is not evidence of functional compliance.

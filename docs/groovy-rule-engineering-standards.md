# Groovy Rule Engineering Standards

## Purpose

One repeatable model for EPM Groovy rules so scripts are maintainable, auditable, and safe in pipeline operations.

## Design principles

- Prefer focused helpers/classes (`Config`, evaluators, payload builders) over one monolithic block of logic.
- Centralize configuration constants (connection names, API versions, variable names, thresholds, naming).
- Fail fast on external call failures; do not allow silent partial success.
- Log enough context for operators to troubleshoot quickly.
- Keep deployable rules **self-contained** — Calculation Manager cannot import shared local files.

## REST and connection standards

- Production rules must use **named connections**; never hard-code credentials.
- Put REST paths and API version constants in one config class.
- Validate response status codes (`2xx` expected) and include response body in failure logs when safe.
- Use `throwVetoException` for terminal failures that should block workflow continuation.
- Prefer Map literals for `json(payload)` arguments (EPM static type checking).

## Script-scope EPM bindings

Keep these at **script scope** (not inside typed helper classes):

- `operation`
- `json()`
- `throwVetoException`
- `rtps`
- `csvIterator`

## File conventions

- Naming: `NNN-domain-action.groovy` (example: `025-run-pipeline-from-rtps.groovy`)
- Required header metadata:
  - Template / Name
  - Owner
  - Created / Updated
  - Purpose
  - Inputs
  - Outputs

## Fiscal and calendar policy

Close-window day cutoffs, fiscal year start month, and file-name prefixes are **per-client Config choices**, not a hard library rule. Document the policy you encode in each script’s `RuleConfig` / `StageConfig`.

## Error handling and logging

Every integration call should log:

- target application/process
- computed period inputs/outputs (when relevant)
- endpoint/operation name
- success or failure status

Error messages should be actionable (what failed, where, and next operator step).

## Testing and validation

- Include deterministic validation examples for boundary conditions when period logic exists.
- Provide optional dry-run mode for payload inspection before live POST/PUT when useful.
- Validate in a lower environment before production:
  - connection resolves
  - payload is accepted
  - target state reflects expected values
  - rerun behavior is idempotent

## Build checklist

1. Confirm requirement and success criteria.
2. Define config constants and class responsibilities.
3. Keep EPM bindings at script scope; type REST responses as `HttpResponse<String>`.
4. Add dry-run / bypass flags only where operators need them.
5. Validate REST error handling paths.
6. Smoke-test in non-prod before attaching to production pipelines.

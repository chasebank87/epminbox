# 040 / 050 — EDM metadata preload and postload validation

Scripts:

- [`groovy-rules/040-validate-edm-metadata-preload.groovy`](../groovy-rules/040-validate-edm-metadata-preload.groovy)
- [`groovy-rules/050-validate-edm-metadata-postload.groovy`](../groovy-rules/050-validate-edm-metadata-postload.groovy)

## Purpose

Guardrails around metadata import:

1. **040 preload** — block risky imports before they run
2. **050 postload** — verify import fidelity immediately after load

Both scripts compare staged EDM dimension export files against target EPM hierarchy members from REST.

```mermaid
flowchart LR
  edmExport[EDM export to inbox] --> preload[040 preload]
  preload --> importMeta[Metadata import]
  importMeta --> postload[050 postload]
```

## Core pattern (both scripts)

1. List repository files under `EDM_EXTRACT_ROOT` (usually `inbox/`).
2. Resolve one EDM file per configured dimension (extension + optional filename token + dimension suffix).
3. Parse distinct member names from the EDM file (first column; skip header/comments).
4. Fetch EPM dimension hierarchy via Get Dimension Details REST; **exclude shared members**.
5. Normalize root aliases (`Account` vs `Accounts`).
6. Hard gate: all EPM members must exist in EDM (EDM-only members are allowed).
7. Soft/secondary gate: count delta thresholds.
8. Print pass/block details; `throwVetoException` if any dimension blocks.

## Named connection

| Connection | Endpoints |
|---|---|
| `EPM_INTERNAL_REST` | `GET /interop/rest/v2/files/list`, `GET /HyperionPlanning/rest/v3/applications/{app}/plantypes/{plantype}/dimensions/{dim}` |

## Config knobs (`ValidationConfig`) — edit in **both** scripts

| Knob | Notes |
|---|---|
| `DIMENSIONS` | Dimensions to validate (template defaults: `Account`, `Entity`) |
| `EPM_PLAN_TYPE` | Cube/plan type (`Consol` for FCCS, `Plan1` for Planning, etc.) |
| `EDM_FILE_NAME_TOKEN` | Filename discriminator (example: `EDMCS_`) to avoid ambiguous matches |
| `EDM_DIMENSION_SUFFIX_MAP` / `EPM_DIMENSION_NAME_MAP` | When EDM labels differ from EPM REST names |
| `EDM_EXTRACT_ROOT` / `EDM_ALLOWED_EXTENSIONS` | Usually `inbox/` and `.txt` |
| Preload thresholds | `MAX_ABSOLUTE_DELTA`, `MAX_PERCENT_DELTA` |
| Postload thresholds | `MAX_POSTLOAD_ABSOLUTE_DELTA`, `MAX_POSTLOAD_PERCENT_DELTA` (tighter) |

Keep 040 and 050 as **two full copies** — do not try to share helpers at runtime.

## Preload-only RTPs (040)

Optional bypass flags (configure as replacement variables on the rule):

- `bypassDeltaCheck`
- `bypassMemberMatchCheck`

Defaults are `false`. Prefer fixing data over bypassing in production.

## Assumptions

- EDM exports are plain text in inbox; one matching file per dimension.
- Shared hierarchies are not synced from EDM → shared EPM members are excluded from comparison.
- EDM-only members are allowed (net-new); EPM-only members block.

## Smoke tests

- Happy path: aligned members → pass.
- Missing EDM file → veto.
- Wrong `EPM_PLAN_TYPE` / dimension → veto or empty hierarchy.
- Same count, wrong members → veto on name containment.
- Count delta within/beyond thresholds → pass/block as configured.

## Reuse checklist

- [ ] Copy both scripts; keep preload → import → postload order
- [ ] Update dimensions and suffix/name maps
- [ ] Set file token and extract root
- [ ] Set correct plan type and connection alias
- [ ] Calibrate preload vs postload thresholds
- [ ] Run smoke tests in non-prod before release

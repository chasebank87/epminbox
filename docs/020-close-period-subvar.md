# 020 — Close-period substitution variables

Script: [`groovy-rules/020-close-period-subvar.groovy`](../groovy-rules/020-close-period-subvar.groovy)

## Purpose

Derive close-period values from the runtime calendar, then either:

- **update** — write substitution variables via Planning REST
- **validate** — compare live subvars to what derivation would produce now (stale check)
- **dryrun** — log derived values only

## Why validate exists

Pipelines often freeze substitution variables in memory at launch. `validate` reads live values via REST and compares them to derivation “now”. If they differ, a scheduled refresh has not caught up since the close window changed.

## Named connection

| Connection | Role |
|---|---|
| `EPM_INTERNAL_REST` | GET/POST `/HyperionPlanning/rest/v3/applications/{app}/substitutionvariables` |

Ensure the connection sends `Content-Type` / `Accept: application/json`.

## Runtime prompts

| RTP | Values |
|---|---|
| `RUN_TASK` | `update` \| `validate` \| `dryrun` |

## Config knobs (`RuleConfig`)

| Knob | Meaning |
|---|---|
| `CLOSE_WINDOW_END_DAY` | Through this day-of-month, use prior month as close period |
| `CLOSE_PERIOD_OFFSET_DURING_CLOSE` / `_AFTER_CLOSE` | Month offsets (typically `-1` / `0`) |
| `FISCAL_YEAR_START_MONTH` | Calendar month number used only for optional FY **label** logging |
| Subvar name constants | `ClosePeriod`, `CloseYear`, `CloseMonth`, `CloseFile`, `CloseSVStatus` (rename per client) |
| `CLOSE_FILE_PREFIX` / `CLOSE_FILE_EXTENSION` | Builds `CloseFile` value (example: `SAP_` + `Apr-26` + `.txt`) |
| `CLOSE_PERIOD_PATTERN` / `CLOSE_MONTH_PATTERN` | `MMM-yy` / `MMM` via `SimpleDateFormat` |
| `DEFAULT_PLAN_TYPE` | Usually `ALL` for app-level subvars |

Default template policy (change per client): day 1–18 → prior month; after day 18 → current month. `CloseYear` is calendar-year based (`FYyy` from the close period’s year). Fiscal-year **label** logging uses `FISCAL_YEAR_START_MONTH` (example April start).

## CloseSVStatus contract

- `update` sets `0` on success; on failure sets `1` in a finally block (best effort)
- `validate` requires `CloseSVStatus=0` before comparing stored close values

## Smoke tests

- `dryrun` on day 1 of month → prior-month close period
- `dryrun` after `CLOSE_WINDOW_END_DAY` → current-month close period
- January during close window → December of prior calendar year
- `validate` with stale subvars → veto
- `update` then `validate` → pass

## Pipeline placement

- Scheduled job: `RUN_TASK=update` (e.g. every 15 minutes)
- Load pipeline go/no-go: same rule with `RUN_TASK=validate`

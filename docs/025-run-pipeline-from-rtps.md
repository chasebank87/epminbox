# 025 — Run pipeline from RTPs

Script: [`groovy-rules/025-run-pipeline-from-rtps.groovy`](../groovy-rules/025-run-pipeline-from-rtps.groovy)

## Purpose

Submit a Data Management / Data Integration **pipeline** job via AIF REST, using runtime prompts for the pipeline name and optional variables.

## Named connection

| Connection | Role |
|---|---|
| `EPM_INTERNAL_REST` (configurable) | Pod base URL + credentials + default JSON headers |

Endpoint: `POST /aif/rest/V1/jobs`

## Runtime prompts (RTPs)

| RTP | Required | Maps to pipeline variable |
|---|---|---|
| `PIPELINE_NAME` | Yes | Job name (`jobName`) |
| `PIPELINE_START_PERIOD` | No | `STARTPERIOD` |
| `PIPELINE_END_PERIOD` | No | `ENDPERIOD` |
| `PIPELINE_IMPORT_MODE` | No | `IMPORTMODE` |
| `PIPELINE_EXPORT_MODE` | No | `EXPORTMODE` |
| `PIPELINE_ATTACH_LOGS` | No | `ATTACH_LOGS` |
| `PIPELINE_SEND_MAIL` | No | `SEND_MAIL` |
| `PIPELINE_SEND_TO` | No | `SEND_TO` |

Values equal to `#Missing` are omitted from the `variables` map.

## Config knobs

Edit `Config` in the script:

- `CONNECTION_NAME`
- `JOBS_ENDPOINT`

## Behavior

1. Read RTPs and build the variables map (skip `#Missing`).
2. POST `{ jobName, jobType: "pipeline", variables }`.
3. Expect HTTP 200 and `jobStatus == "RUNNING"`.
4. On failure, `throwVetoException` with status/body.

This rule **starts** the pipeline; it does not wait for completion.

## Smoke tests

- Valid pipeline name → job starts; log shows job ID.
- Missing / invalid pipeline name → veto with API body.
- `#Missing` optional RTPs → payload omits those variables.

## Pipeline placement

Use as a Calculation Manager step that launches another pipeline (orchestration hop), or as a manual “run with prompts” rule.

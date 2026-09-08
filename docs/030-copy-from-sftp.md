# 030 — Copy from SFTP to inbox

Script: [`groovy-rules/030-copy-from-sftp.groovy`](../groovy-rules/030-copy-from-sftp.groovy)

## Purpose

1. Read one or more substitution variables (typically close-period values).
2. Build the expected SFTP source file name from config + a chosen subvar value.
3. Delete an existing staged inbox file if present.
4. Copy from SFTP via the named copy connection and wait for the v2 job to finish.

## Named connections

| Connection | Role |
|---|---|
| `EPM_INTERNAL_REST` | Subvar GET, files list/delete, job status poll |
| `EPM_COPY_FROM_SFTP` | `POST /interop/rest/v2/config/services/copyfromsftp` (SFTP host/credentials live on the connection) |

## Config knobs (`StageConfig`)

| Knob | Meaning |
|---|---|
| `SOURCE_FILE_PREFIX` / `SOURCE_FILE_SUFFIX` | Builds remote file token (example: `sapfile_` + `Apr-26` + `.txt`) |
| `SOURCE_NAME_SUBVAR` | Which subvar value is embedded in the source file name (default `ClosePeriod`) |
| `STAGED_FILE_NAME` / `INBOX_PATH_PREFIX` | Target path (example: `inbox/stagedload.txt`) |
| Subvar name constants | Which close subvars to read/log |
| Poll attempt cap | Fixed loop + `sleep` (no wall clock) |

## Behavior

1. GET required subvars; veto if missing/empty.
2. Build `sourceFileName` and `targetFilePath`.
3. List repository files; if staged file exists, delete via `/interop/rest/v3/files/delete`.
4. POST copyfromsftp with `{ sourceFileName, filePath }`.
5. Poll Job Status URL until status `0` (success), non-`-1` failure, or timeout.

## Smoke tests

- Happy path: source exists on SFTP → staged file appears in inbox.
- Existing staged file → deleted then replaced.
- Missing subvar → veto before copy.
- Missing remote file / copy failure → veto with status body.

## Pipeline placement

Typically after close-period subvars are updated/validated (`020`), before a data load that consumes the staged inbox file.

## Prerequisites

- Copy-from-SFTP connection must already encode host, credentials, and default remote path behavior.
- Source file naming on the SFTP server must match `SOURCE_FILE_PREFIX` + subvar value + `SOURCE_FILE_SUFFIX`.

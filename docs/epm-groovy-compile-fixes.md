# EPM Groovy Compile Fixes Log

Track compile/runtime constraints discovered in Oracle EPM Groovy so templates avoid repeat failures.

## Imports and date APIs

- **Blocked:** `java.time.format.DateTimeFormatter` (and often other `java.time` APIs)
- **Use instead:** `java.util.Calendar`, `java.util.Date`, `java.text.SimpleDateFormat`
- **Rule:** Avoid `java.time` unless verified as allowed in the target pod.

## Script-scope helpers vs typed classes

- **Symptoms:** `No such property: application for class: java.lang.Object`, missing `json(...)` / `throwVetoException(...)` inside custom classes
- **Cause:** `operation`, `json()`, `throwVetoException()` are script-scope features and are not reliably resolved inside typed classes under static checking
- **Fix:**
  - Keep those calls at script scope
  - Cast map values used in arithmetic (`period.month as int`)
  - Prefer Map literals for `json(payload)` — incremental `def m = [:]; m["k"]=v` can fail static checks
  - Keep `HttpResponse<String> response = operation...post()...body(json(payload)).asString()` as one typed assignment

## REST response typing

- **Symptom:** `No such property: status for class: java.lang.Object`
- **Cause:** Declaring the REST result as `def` makes static checking treat it as `Object`
- **Fix:** Declare `HttpResponse<String>` and type helper parameters the same way

## Media type on Planning APIs

- **Symptom:** HTTP `415 Unsupported Media Type` on substitution-variable endpoints
- **Fix:** Configure `Content-Type: application/json` and `Accept: application/json` on the named connection (preferred over per-call headers in script)

## Polling without wall clock

- **Blocked:** `java.lang.System.currentTimeMillis()`
- **Fix:** For fixed-interval polls, track consecutive unchanged polls (or fixed attempt caps) instead of reading the system clock

## Binding variables from methods

- **Symptom:** `No such property: DEBUG_STEP for class: groovy.lang.Binding`
- **Cause:** Methods cannot read/write script Binding variables
- **Fix:** Keep mutable state on a class instance and pass it into methods

## FCCS whitelist notes (calc-adjacent)

- `Cube.executeCalcScript(String)` is not on the FCCS Groovy whitelist
- FCCS restricted dimensions cannot be in `FIX` or on the LHS of equations (Scenario, Year, Period, Entity, View, Consolidation, Currency) — drive them from run POV instead

## Reuse checklist before deploy

1. Validate imports against known allowed classes.
2. Confirm script-scope EPM helpers are not hidden inside typed helper classes.
3. Cast map values used in arithmetic/comparisons.
4. Validate in Calculation Manager before attaching to forms/jobs/pipelines.

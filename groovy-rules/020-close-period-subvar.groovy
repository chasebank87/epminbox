/*
 Template: Groovy rule
 Name: 020-close-period-subvar.groovy
 Owner:
 Created: 2026-09-08
 Updated: 2026-09-08

 Purpose:
   Derive close-period substitution variables and either update them,
   validate them for a load pipeline, or dry-run the derivation.

 Runtime prompt (text):
   RUN_TASK = update | validate | dryrun

 Who calls each mode:
   - update   : scheduled job refreshes app substitution variables
   - validate : load pipeline go/no-go step (same rule, RUN_TASK=validate)
   - dryrun   : manual test — log derived values only, no REST writes

 Substitution variables (update writes all five; rename via RuleConfig):
   - ClosePeriod  : MMM-yy (e.g. Apr-26)
   - CloseYear    : FYyy from calendar year of close period (e.g. FY26)
   - CloseMonth   : MMM (e.g. Apr)
   - CloseFile    : <prefix><ClosePeriod><ext> (e.g. SAP_Apr-26.txt)
   - CloseSVStatus: 0 = last update succeeded, 1 = last update failed

 CloseSVStatus contract:
   - update sets 0 on success; on failure sets 1 in a finally block (best effort)
   - validate requires CloseSVStatus=0 before comparing stored close values

 Validate stale check (why it exists):
   Pipelines freeze substitution variables in memory at launch. validate reads live
   values via REST and compares them to what derivation would produce now. If they
   differ, the scheduled refresh has not caught up since the close window changed.

 Close window rule (configure in RuleConfig):
   - Day 1 through CLOSE_WINDOW_END_DAY: close period = prior month (offset -1)
   - After CLOSE_WINDOW_END_DAY: close period = current month (offset 0)
   Fiscal year label for logging only: configure FISCAL_YEAR_START_MONTH.

 Inputs:
   - RUN_TASK runtime prompt
   - EPM_INTERNAL_REST named connection
 Outputs:
   - Updated or validated substitution variables, or dry-run logs
*/
/*RTPS: {RUN_TASK}*/

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// --- Configuration ---

class RuleConfig {
    static final String CONNECTION_NAME = "EPM_INTERNAL_REST"
    static final String REST_BASE_PATH = "/HyperionPlanning/rest"
    static final String API_VERSION = "v3"
    static final String SUB_VAR_ENDPOINT_SUFFIX = "substitutionvariables"

    // Rename these to match the target application's substitution variables.
    static final String SUB_VAR_CLOSE_PERIOD = "ClosePeriod"
    static final String SUB_VAR_CLOSE_YEAR = "CloseYear"
    static final String SUB_VAR_CLOSE_MONTH = "CloseMonth"
    static final String SUB_VAR_CLOSE_FILE = "CloseFile"
    static final String SUB_VAR_CLOSE_SV_STATUS = "CloseSVStatus"

    static final String CLOSE_SV_STATUS_SUCCESS = "0"
    static final String CLOSE_SV_STATUS_ERROR = "1"

    // CloseFile value = PREFIX + ClosePeriod + EXTENSION (configure per client).
    static final String CLOSE_FILE_PREFIX = "SAP_"
    static final String CLOSE_FILE_EXTENSION = ".txt"
    static final String DEFAULT_PLAN_TYPE = "ALL"
    static final String CLOSE_PERIOD_PATTERN = "MMM-yy"
    static final String CLOSE_MONTH_PATTERN = "MMM"

    // Through CLOSE_WINDOW_END_DAY use prior month; after that use current month.
    static final int CLOSE_WINDOW_PRESTART_DAYS = 5
    static final int CLOSE_WINDOW_END_DAY = 18
    static final int CLOSE_PERIOD_OFFSET_DURING_CLOSE = -1
    static final int CLOSE_PERIOD_OFFSET_AFTER_CLOSE = 0
    // Calendar month number (1=January) used only for FY label logging — not CloseYear value.
    static final int FISCAL_YEAR_START_MONTH = Calendar.APRIL + 1
    static final String FISCAL_YEAR_START_MONTH_LABEL = "APRIL"

    static final String TASK_UPDATE = "update"
    static final String TASK_VALIDATE = "validate"
    static final String TASK_DRYRUN = "dryrun"
}

// --- Close period derivation (window + optional fiscal label) ---

// Through close end day use prior month; after that use current month.
int determineClosePeriodOffset(int dayOfMonth) {
    if (dayOfMonth <= RuleConfig.CLOSE_WINDOW_END_DAY) {
        return RuleConfig.CLOSE_PERIOD_OFFSET_DURING_CLOSE
    }
    return RuleConfig.CLOSE_PERIOD_OFFSET_AFTER_CLOSE
}

Map deriveClosePeriod(Date runtimeDate) {
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(runtimeDate)
    int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
    int closePeriodOffset = determineClosePeriodOffset(dayOfMonth)
    calendar.add(Calendar.MONTH, closePeriodOffset)

    return [
        year : calendar.get(Calendar.YEAR),
        month: calendar.get(Calendar.MONTH) + 1
    ]
}

// FY label for operator logs only (Apr->Mar fiscal year).
String deriveFiscalYearLabel(Map period) {
    int month = period.month as int
    int year = period.year as int
    int fiscalYearEnd = month >= RuleConfig.FISCAL_YEAR_START_MONTH ? year + 1 : year
    return "FY${String.format('%02d', fiscalYearEnd % 100)}"
}

String formatPeriodWithPattern(Map period, String pattern) {
    Calendar calendar = Calendar.getInstance()
    calendar.clear()
    calendar.set(Calendar.YEAR, period.year as int)
    calendar.set(Calendar.MONTH, (period.month as int) - 1)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    return new SimpleDateFormat(pattern, Locale.US).format(calendar.getTime())
}

String toClosePeriodValue(Map period) {
    return formatPeriodWithPattern(period, RuleConfig.CLOSE_PERIOD_PATTERN)
}

String toCloseYearValue(Map period) {
    int year = period.year as int
    return String.format("FY%02d", year % 100)
}

String toCloseMonthValue(Map period) {
    return formatPeriodWithPattern(period, RuleConfig.CLOSE_MONTH_PATTERN)
}

String toCloseFileValue(String closePeriodValue) {
    return "${RuleConfig.CLOSE_FILE_PREFIX}${closePeriodValue}${RuleConfig.CLOSE_FILE_EXTENSION}"
}

Map buildDerivedCloseValues(Date runtimeDate) {
    Map period = deriveClosePeriod(runtimeDate)
    String closePeriodValue = toClosePeriodValue(period)

    return [
        period           : period,
        fiscalYearLabel  : deriveFiscalYearLabel(period),
        closePeriodValue : closePeriodValue,
        closeYearValue   : toCloseYearValue(period),
        closeMonthValue  : toCloseMonthValue(period),
        closeFileValue   : toCloseFileValue(closePeriodValue)
    ]
}

void logDerivedCloseValues(Date runtimeDate, Map derived) {
    println("Runtime date: ${runtimeDate}")
    println("Derived close period: ${derived.closePeriodValue} (${derived.fiscalYearLabel})")
    println("Derived close year: ${derived.closeYearValue}")
    println("Derived close month: ${derived.closeMonthValue}")
    println("Derived close file: ${derived.closeFileValue}")
}

// --- REST: substitution variable GET/POST ---

Map buildSubVarPayload(Map variableValues) {
    List items = new ArrayList()
    variableValues.each { variableName, variableValue ->
        items.add([
            name    : variableName == null ? "" : variableName.toString(),
            value   : variableValue == null ? "" : variableValue.toString(),
            planType: RuleConfig.DEFAULT_PLAN_TYPE
        ])
    }
    return [items: items]
}

String buildSubVarPostEndpoint(String applicationName) {
    return "${RuleConfig.REST_BASE_PATH}/${RuleConfig.API_VERSION}/applications/${applicationName}/${RuleConfig.SUB_VAR_ENDPOINT_SUFFIX}"
}

String buildSubVarGetEndpoint(String applicationName, String subVarName) {
    return "${RuleConfig.REST_BASE_PATH}/${RuleConfig.API_VERSION}/applications/${applicationName}/${RuleConfig.SUB_VAR_ENDPOINT_SUFFIX}/${subVarName}"
}

String extractJsonStringValue(String jsonText, String keyName) {
    def matcher = (jsonText =~ /"${keyName}"\s*:\s*"([^"]*)"/)
    return matcher.find() ? matcher.group(1) : null
}

void ensureHttp2xx(HttpResponse<String> response, String operationName) {
    if (!(200..299).contains(response.status)) {
        throwVetoException("${operationName} failed. HTTP=${response.status}, Body=${response.body}")
    }
}

void postSubstitutionVariables(String applicationName, Map variableValues) {
    Map payload = buildSubVarPayload(variableValues)
    String endpoint = buildSubVarPostEndpoint(applicationName)

    HttpResponse<String> response = operation.application
        .getConnection(RuleConfig.CONNECTION_NAME)
        .post(endpoint)
        .body(json(payload))
        .asString()

    ensureHttp2xx(response, "Set substitution variables via REST (endpoint=${endpoint})")
}

String getSubVarValue(String applicationName, String subVarName) {
    String endpoint = buildSubVarGetEndpoint(applicationName, subVarName)
    HttpResponse<String> response = operation.application
        .getConnection(RuleConfig.CONNECTION_NAME)
        .get(endpoint)
        .asString()

    ensureHttp2xx(response, "Get substitution variable ${subVarName}")

    String value = extractJsonStringValue(response.body, "value")
    if (value == null || value.trim().isEmpty()) {
        throwVetoException(
            "Substitution variable ${subVarName} is missing or empty. Body=${response.body}"
        )
    }
    return value.trim()
}

// Best-effort flag so validate can block loads after a failed update run.
void markCloseSvStatusError(String applicationName) {
    try {
        postSubstitutionVariables(applicationName, [
            (RuleConfig.SUB_VAR_CLOSE_SV_STATUS): RuleConfig.CLOSE_SV_STATUS_ERROR
        ])
    } catch (Exception statusEx) {
        println("Unable to set CloseSVStatus=1: ${statusEx.message}")
    }
}

// --- RUN_TASK parsing ---

String normalizeTaskValue(String rawTask) {
    String task = rawTask == null ? "" : rawTask.toString().trim().toLowerCase()
    if (task.isEmpty() || "#missing".equals(task)) {
        return ""
    }
    if ("dry-run".equals(task) || "dry_run".equals(task)) {
        return RuleConfig.TASK_DRYRUN
    }
    return task
}

String readRunTask() {
    String taskRaw = rtps.RUN_TASK
    println("RUN_TASK is : ${taskRaw}")

    String task = normalizeTaskValue(taskRaw)
    if (task.isEmpty()) {
        throwVetoException("RUN_TASK runtime prompt is required. Allowed values: update, validate, dryrun.")
    }
    if (!RuleConfig.TASK_UPDATE.equals(task) &&
        !RuleConfig.TASK_VALIDATE.equals(task) &&
        !RuleConfig.TASK_DRYRUN.equals(task)) {
        throwVetoException("Invalid RUN_TASK value '${taskRaw}'. Allowed values: update, validate, dryrun.")
    }
    return task
}

// --- Mode handlers: dryrun | validate | update ---

void runDryRun(Date runtimeDate, Map derived) {
    logDerivedCloseValues(runtimeDate, derived)
    println("Dry run complete. No substitution variables were updated.")
}

void runValidate(Date runtimeDate, Map derived) {
    String applicationName = operation.application.name
    logDerivedCloseValues(runtimeDate, derived)

    String closeSvStatus = getSubVarValue(applicationName, RuleConfig.SUB_VAR_CLOSE_SV_STATUS)
    if (RuleConfig.CLOSE_SV_STATUS_ERROR.equals(closeSvStatus)) {
        throwVetoException("CloseSVStatus=1 from the last update run. Fix before loading.")
    }
    if (!RuleConfig.CLOSE_SV_STATUS_SUCCESS.equals(closeSvStatus)) {
        throwVetoException("CloseSVStatus must be 0 before loading. Actual='${closeSvStatus}'.")
    }

    List compareItems = [
        [name: RuleConfig.SUB_VAR_CLOSE_PERIOD, expected: derived.closePeriodValue],
        [name: RuleConfig.SUB_VAR_CLOSE_YEAR, expected: derived.closeYearValue],
        [name: RuleConfig.SUB_VAR_CLOSE_MONTH, expected: derived.closeMonthValue],
        [name: RuleConfig.SUB_VAR_CLOSE_FILE, expected: derived.closeFileValue]
    ]

    List mismatches = []
    compareItems.each { item ->
        String subVarName = item.name as String
        String expectedValue = item.expected as String
        String storedValue = getSubVarValue(applicationName, subVarName)
        println("Stored ${subVarName}=${storedValue}")
        if (!expectedValue.equals(storedValue)) {
            mismatches.add("${subVarName} stored='${storedValue}' expected='${expectedValue}'")
        }
    }

    if (!mismatches.isEmpty()) {
        mismatches.each { println("STALE: ${it}") }
        throwVetoException("Close subvariables are stale. Wait for the next successful update run.")
    }

    println("Close subvariable validation passed.")
}

void runUpdate(Map derived) {
    String applicationName = operation.application.name
    boolean updateSucceeded = false

    try {
        Map postValues = [
            (RuleConfig.SUB_VAR_CLOSE_PERIOD)   : derived.closePeriodValue,
            (RuleConfig.SUB_VAR_CLOSE_YEAR)     : derived.closeYearValue,
            (RuleConfig.SUB_VAR_CLOSE_MONTH)    : derived.closeMonthValue,
            (RuleConfig.SUB_VAR_CLOSE_FILE)     : derived.closeFileValue,
            (RuleConfig.SUB_VAR_CLOSE_SV_STATUS): RuleConfig.CLOSE_SV_STATUS_SUCCESS
        ]
        postSubstitutionVariables(applicationName, postValues)
        updateSucceeded = true
        println("Successfully updated substitution variables for app ${applicationName}.")
    } finally {
        if (!updateSucceeded) {
            markCloseSvStatusError(applicationName)
        }
    }
}

void runRule(Date runtimeDate) {
    String task = readRunTask()
    Map derived = buildDerivedCloseValues(runtimeDate)

    println("Dispatching RUN_TASK=${task}")

    if (RuleConfig.TASK_DRYRUN.equals(task)) {
        runDryRun(runtimeDate, derived)
        return
    }
    if (RuleConfig.TASK_VALIDATE.equals(task)) {
        runValidate(runtimeDate, derived)
        return
    }

    logDerivedCloseValues(runtimeDate, derived)
    runUpdate(derived)
}

// --- Entrypoint ---
runRule(new Date())

/*
   Validation examples for default close schedule (day 1-18 -> prior month):
 - runtime=2026-05-01 -> closePeriod=Apr-26
 - runtime=2026-05-18 -> closePeriod=Apr-26
 - runtime=2026-05-19 -> closePeriod=May-26
 - runtime=2026-01-10 -> closePeriod=Dec-25  // year crossover
 Adjust examples after changing CLOSE_WINDOW_END_DAY or offsets.
*/

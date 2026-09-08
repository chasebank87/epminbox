/*
 Template: Groovy rule
 Name: 025-run-pipeline-from-rtps.groovy
 Owner:
 Created: 2026-09-08
 Updated: 2026-09-08
 Purpose:
   - Launch a Data Management pipeline job via AIF REST using runtime prompts
 Inputs:
   - RTPs: PIPELINE_NAME (required), optional period/mode/mail variables
   - Named connection EPM_INTERNAL_REST
 Outputs:
   - Pipeline job started (jobId logged) or throwVetoException
*/
/* RTPS: {PIPELINE_NAME} {PIPELINE_START_PERIOD} {PIPELINE_END_PERIOD} {PIPELINE_IMPORT_MODE} {PIPELINE_EXPORT_MODE} {PIPELINE_ATTACH_LOGS} {PIPELINE_SEND_MAIL} {PIPELINE_SEND_TO} */

import groovy.json.JsonSlurper

/* =========================
   CONFIGURATION
   ========================= */
class Config {
    // Named connection = pod base URL + credentials + default headers only (no API path here).
    static final String CONNECTION_NAME = "EPM_INTERNAL_REST"
    static final String JOBS_ENDPOINT = "/aif/rest/V1/jobs"
}

/* =========================
   RTP EXTRACTION
   ========================= */
String pipeline     = rtps.PIPELINE_NAME
String startPeriod  = rtps.PIPELINE_START_PERIOD
String endPeriod    = rtps.PIPELINE_END_PERIOD
String importMode   = rtps.PIPELINE_IMPORT_MODE
String exportMode   = rtps.PIPELINE_EXPORT_MODE
String attachLogs   = rtps.PIPELINE_ATTACH_LOGS
String sendMail     = rtps.PIPELINE_SEND_MAIL
String sendTo       = rtps.PIPELINE_SEND_TO

/* =========================
   DEBUG LOGGING
   ========================= */
println("Pipeline Name is : $pipeline")
println("Start Period is : $startPeriod")
println("End Period is : $endPeriod")
println("Import Mode is : $importMode")
println("Export Mode is : $exportMode")
println("Attach Logs is : $attachLogs")
println("Send Mail is : $sendMail")
println("Send To is : $sendTo")
println("")

/* =========================
   BUILD VARIABLES MAP
   ========================= */
def variables = [:]

if (startPeriod != "#Missing") variables["STARTPERIOD"] = startPeriod
if (endPeriod   != "#Missing") variables["ENDPERIOD"]   = endPeriod
if (importMode  != "#Missing") variables["IMPORTMODE"]  = importMode
if (exportMode  != "#Missing") variables["EXPORTMODE"]  = exportMode
if (attachLogs  != "#Missing") variables["ATTACH_LOGS"] = attachLogs
if (sendMail    != "#Missing") variables["SEND_MAIL"]   = sendMail
if (sendTo      != "#Missing") variables["SEND_TO"]     = sendTo

/* =========================
   BUILD PAYLOAD
   ========================= */
def payload = [
    "jobName": pipeline,
    "jobType": "pipeline",
    "variables": variables
]

println(json(payload))
println("POST ${Config.JOBS_ENDPOINT} via connection ${Config.CONNECTION_NAME}")
println("")

/* =========================
   EXECUTE API CALL
   ========================= */
HttpResponse<String> jsonResponse = operation.application
    .getConnection(Config.CONNECTION_NAME)
    .post(Config.JOBS_ENDPOINT)
    .body(json(payload))
    .asString()

println("")

/* =========================
   RESPONSE HANDLING
   ========================= */
if (jsonResponse.status != 200) {
    throwVetoException(
        "Pipeline API request failed with status code: ${jsonResponse.status}, body: ${jsonResponse.body}"
    )
}

def responseBody = jsonResponse.body
    ? new JsonSlurper().parseText(jsonResponse.body) as Map
    : [:]

if (!responseBody.containsKey('jobStatus') || responseBody.get('jobStatus') != "RUNNING") {
    throwVetoException("Pipeline job failed to start. Response: ${jsonResponse.body}")
}

println("Pipeline job started successfully. Job ID: ${responseBody.get('jobId')}")

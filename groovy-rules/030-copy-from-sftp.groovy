/*
 Template: Groovy rule
 Name: 030-copy-from-sftp.groovy
 Owner:
 Created: 2026-09-08
 Updated: 2026-09-08
 Purpose:
   - Read configured substitution variables (typically close-period values)
   - Build expected SFTP source file name from prefix + subvar value + suffix
   - Stage file to a fixed inbox path via copyfromsftp
   - Replace existing staged file if present
 Inputs:
   - Named connections: EPM_INTERNAL_REST, EPM_COPY_FROM_SFTP
   - Substitution variables listed in StageConfig
 Outputs:
   - Staged inbox file ready for a downstream load, or throwVetoException
*/

class StageConfig {
    // Internal EPM REST connection (auth + headers)
    static final String INTERNAL_CONNECTION_NAME = "EPM_INTERNAL_REST"
    // Connection configured to execute Copy from SFTP (including required defaults)
    static final String COPY_FROM_SFTP_CONNECTION_NAME = "EPM_COPY_FROM_SFTP"

    // Planning app + substitution variables (rename to match the target app)
    static final String SUB_VAR_API_VERSION = "v3"
    static final String SUB_VAR_CLOSE_PERIOD_NAME = "ClosePeriod"
    static final String SUB_VAR_CLOSE_YEAR_NAME = "CloseYear"
    static final String SUB_VAR_CLOSE_MONTH_NAME = "CloseMonth"
    // Which subvar value is embedded in the SFTP source file name.
    static final String SOURCE_NAME_SUBVAR = SUB_VAR_CLOSE_PERIOD_NAME

    // REST endpoints (Oracle Cloud EPM REST APIs)
    static final String FILES_LIST_ENDPOINT = "/interop/rest/v2/files/list"
    static final String FILES_DELETE_ENDPOINT = "/interop/rest/v3/files/delete"
    static final String FILES_COPY_FROM_SFTP_ENDPOINT = "/interop/rest/v2/config/services/copyfromsftp"
    static final String STATUS_REL_TEXT = "\"rel\":\"Job Status\""

    // Naming — configure to match the remote SFTP file convention.
    static final String SOURCE_FILE_PREFIX = "sapfile_"
    static final String SOURCE_FILE_SUFFIX = ".txt"
    static final String STAGED_FILE_NAME = "stagedload.txt"
    static final String INBOX_PATH_PREFIX = "inbox/"

    // Source file name token passed to the Copy from SFTP connection.
    // Connection setup should handle SFTP server details and credentials.
    static final String SOURCE_FILE_NAME_PAYLOAD_KEY = "sourceFileName"

    // Fixed poll attempts (EPM may block System.currentTimeMillis).
    static final int MAX_STATUS_POLL_ATTEMPTS = 20
    static final int STATUS_POLL_SLEEP_MS = 1000
}

class EndpointBuilder {
    String buildSubVarEndpoint(String applicationName, String subVarName) {
        return "/HyperionPlanning/rest/${StageConfig.SUB_VAR_API_VERSION}/applications/${applicationName}/substitutionvariables/${subVarName}"
    }
}

class StageFileNameBuilder {
    String buildSourceFileName(String closePeriod) {
        return "${StageConfig.SOURCE_FILE_PREFIX}${closePeriod}${StageConfig.SOURCE_FILE_SUFFIX}"
    }

    String buildStagedFilePath() {
        return "${StageConfig.INBOX_PATH_PREFIX}${StageConfig.STAGED_FILE_NAME}"
    }
}

class JsonResponseParser {
    String extractJsonValue(String jsonText, String keyName) {
        def matcher = (jsonText =~ /"${keyName}"\s*:\s*"([^"]+)"/)
        return matcher.find() ? matcher.group(1) : null
    }

    int extractJsonInt(String jsonText, String keyName) {
        def matcher = (jsonText =~ /"${keyName}"\s*:\s*(-?\d+)/)
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MIN_VALUE
    }

    String extractV2JobStatusUrl(String jsonText) {
        if (!jsonText.contains(StageConfig.STATUS_REL_TEXT)) {
            return null
        }
        def matcher = (jsonText =~ /(https?:\/\/[^\s"]+\/interop\/rest\/v2\/status\/jobs\/\d+)/)
        return matcher.find() ? matcher.group(1) : null
    }
}

void ensureHttp2xx(HttpResponse<String> response, String operationName) {
    if (!(200..299).contains(response.status)) {
        throwVetoException("${operationName} failed. HTTP=${response.status}, Body=${response.body}")
    }
}

void waitForV2Job(String jobStatusUrl, String operationName) {
    JsonResponseParser parser = new JsonResponseParser()
    if (jobStatusUrl == null) {
        throwVetoException("${operationName} failed: job status URL missing in response.")
    }

    for (int attempt = 1; attempt <= StageConfig.MAX_STATUS_POLL_ATTEMPTS; attempt++) {
        HttpResponse<String> statusResp = operation.application
            .getConnection(StageConfig.INTERNAL_CONNECTION_NAME)
            .get(jobStatusUrl)
            .asString()

        ensureHttp2xx(statusResp, "${operationName} status poll")
        int status = parser.extractJsonInt(statusResp.body, "status")
        if (status == 0) {
            println("${operationName} completed successfully.")
            return
        }
        if (status != -1) {
            throwVetoException("${operationName} failed with status=${status}, body=${statusResp.body}")
        }
        sleep(StageConfig.STATUS_POLL_SLEEP_MS)
    }
    throwVetoException("${operationName} timed out waiting for completion.")
}

String getRequiredSubVarValue(String applicationName, String subVarName) {
    EndpointBuilder endpointBuilder = new EndpointBuilder()
    JsonResponseParser parser = new JsonResponseParser()
    String endpoint = endpointBuilder.buildSubVarEndpoint(applicationName, subVarName)
    HttpResponse<String> response = operation.application
        .getConnection(StageConfig.INTERNAL_CONNECTION_NAME)
        .get(endpoint)
        .asString()
    ensureHttp2xx(response, "Get substitution variable ${subVarName}")

    String subVarValue = parser.extractJsonValue(response.body, "value")
    if (subVarValue == null || subVarValue.trim().isEmpty()) {
        throwVetoException("Substitution variable ${subVarName} is missing or empty. Body=${response.body}")
    }
    return subVarValue
}

boolean repositoryFileExists(String fileName) {
    HttpResponse<String> response = operation.application
        .getConnection(StageConfig.INTERNAL_CONNECTION_NAME)
        .get(StageConfig.FILES_LIST_ENDPOINT)
        .asString()
    ensureHttp2xx(response, "List repository files")

    // Exact name match in returned items.
    String escaped = fileName.replace(".", "\\.")
    def matcher = (response.body =~ /"name"\s*:\s*"${escaped}"/)
    return matcher.find()
}

void deleteRepositoryFile(String filePath) {
    JsonResponseParser parser = new JsonResponseParser()
    Map payload = [fileName: filePath]
    HttpResponse<String> response = operation.application
        .getConnection(StageConfig.INTERNAL_CONNECTION_NAME)
        .post(StageConfig.FILES_DELETE_ENDPOINT)
        .body(json(payload))
        .asString()
    ensureHttp2xx(response, "Delete file ${filePath}")

    int status = parser.extractJsonInt(response.body, "status")
    if (status != 0) {
        throwVetoException("Delete file ${filePath} failed with status=${status}, body=${response.body}")
    }
}

void removeExistingStagedFileIfPresent() {
    StageFileNameBuilder fileNameBuilder = new StageFileNameBuilder()
    String stagedPath = fileNameBuilder.buildStagedFilePath()

    if (!repositoryFileExists(StageConfig.STAGED_FILE_NAME)) {
        println("No existing ${StageConfig.STAGED_FILE_NAME} found in repository.")
        return
    }

    println("Existing ${StageConfig.STAGED_FILE_NAME} found. Deleting before staging new file.")
    deleteRepositoryFile(stagedPath)
}

void copyFromSftpToInbox(String sourceFileName, String targetFilePath) {
    JsonResponseParser parser = new JsonResponseParser()
    Map payload = [
        (StageConfig.SOURCE_FILE_NAME_PAYLOAD_KEY): sourceFileName,
        filePath                                 : targetFilePath
    ]

    HttpResponse<String> response = operation.application
        .getConnection(StageConfig.COPY_FROM_SFTP_CONNECTION_NAME)
        .post(StageConfig.FILES_COPY_FROM_SFTP_ENDPOINT)
        .body(json(payload))
        .asString()
    ensureHttp2xx(response, "Copy from SFTP")

    String jobStatusUrl = parser.extractV2JobStatusUrl(response.body)
    waitForV2Job(jobStatusUrl, "Copy from SFTP")
}

void runStageLoad() {
    StageFileNameBuilder fileNameBuilder = new StageFileNameBuilder()
    String applicationName = operation.application.name
    String closePeriod = getRequiredSubVarValue(applicationName, StageConfig.SUB_VAR_CLOSE_PERIOD_NAME)
    String closeYear = getRequiredSubVarValue(applicationName, StageConfig.SUB_VAR_CLOSE_YEAR_NAME)
    String closeMonth = getRequiredSubVarValue(applicationName, StageConfig.SUB_VAR_CLOSE_MONTH_NAME)
    String sourceNameToken = StageConfig.SOURCE_NAME_SUBVAR.equals(StageConfig.SUB_VAR_CLOSE_PERIOD_NAME)
        ? closePeriod
        : getRequiredSubVarValue(applicationName, StageConfig.SOURCE_NAME_SUBVAR)

    String sourceFileName = fileNameBuilder.buildSourceFileName(sourceNameToken)
    String targetPath = fileNameBuilder.buildStagedFilePath()

    println("ClosePeriod=${closePeriod}")
    println("CloseYear=${closeYear}")
    println("CloseMonth=${closeMonth}")
    println("Source name subvar=${StageConfig.SOURCE_NAME_SUBVAR} value=${sourceNameToken}")
    println("Source file token=${sourceFileName}")
    println("Target inbox file=${targetPath}")

    removeExistingStagedFileIfPresent()
    copyFromSftpToInbox(sourceFileName, targetPath)

    println("Staging complete. ${sourceFileName} requested for copy to ${targetPath}.")
}

runStageLoad()

/*
 Template: Groovy rule
 Name: 050-validate-edm-metadata-postload.groovy
 Owner:
 Created: 2026-09-08
 Updated: 2026-09-08
 Purpose:
   - Validate metadata import results by reconciling staged EDM files vs target EPM metadata from REST after load
   - Exclude shared members from the EPM set (shared hierarchies are not synced from EDM)
 Inputs:
   - EDM export files staged in inbox (CSV/TXT with header row)
   - Target EPM metadata hierarchy retrieved via Get Dimension Details REST API
 Outputs:
   - Pass/fail post-load validation
*/

import groovy.json.JsonSlurper

class ValidationConfig {
    static final int MAX_POSTLOAD_ABSOLUTE_DELTA = 25
    static final BigDecimal MAX_POSTLOAD_PERCENT_DELTA = 0.02G
    static final int MAX_MISMATCH_SAMPLE = 15

    static final String INTERNAL_CONNECTION_NAME = "EPM_INTERNAL_REST"
    static final String FILES_LIST_ENDPOINT = "/interop/rest/v2/files/list"
    static final String REST_BASE_PATH = "/HyperionPlanning/rest"
    static final String API_VERSION = "v3"
    // Target EPM plan type / cube name. Configure per pipeline.
    // Examples: "Consol" (FCCS), "Plan1" (Planning).
    static final String EPM_PLAN_TYPE = "Consol"

    // EDM exports are plain text files available in repository Inbox/Outbox context.
    static final String EDM_EXTRACT_ROOT = "inbox/"

    // Dimension list to validate — keep in sync with the preload script.
    static final List<String> DIMENSIONS = [
        "Account",
        "Entity"
    ]
    // Optional source-specific suffix overrides when EDM and target EPM naming differs.
    static final Map<String, String> EDM_DIMENSION_SUFFIX_MAP = [:]
    // Mapping for target EPM REST dimension names when EDM labels differ.
    static final Map<String, String> EPM_DIMENSION_NAME_MAP = [:]

    // Dynamic suffix matching by dimension name.
    static final List<String> EDM_ALLOWED_EXTENSIONS = [".txt"]
    // Filename discriminator to avoid ambiguity when multiple .txt files are in inbox.
    // Example: "EDMCS_" or a client/app-specific token.
    static final String EDM_FILE_NAME_TOKEN = "EDMCS_"
}

class DeltaEvaluator {
    Map<String, Object> evaluate(String dimensionName, int sourceCount, int appCount) {
        int deltaAbs = Math.abs(sourceCount - appCount)
        BigDecimal deltaPct = appCount == 0
            ? (sourceCount == 0 ? 0G : 1G)
            : (new BigDecimal(deltaAbs) / new BigDecimal(Math.abs(appCount)))

        boolean blocked = (deltaAbs > ValidationConfig.MAX_POSTLOAD_ABSOLUTE_DELTA) || (deltaPct > ValidationConfig.MAX_POSTLOAD_PERCENT_DELTA)
        return [
            status   : blocked ? "blocked" : "ok",
            source   : sourceCount,
            app      : appCount,
            deltaAbs : deltaAbs,
            deltaPct : deltaPct
        ]
    }
}

Set<String> extractDistinctMembersFromEdmFile(String filePath) {
    Set<String> memberKeys = new LinkedHashSet<String>()
    boolean isFirstRow = true

    csvIterator(filePath).each { row ->
        if (row == null || row.length == 0) {
            return
        }

        String firstCol = row[0] == null ? "" : row[0].toString().trim()
        if (firstCol.isEmpty()) {
            return
        }

        if (isFirstRow) {
            isFirstRow = false
            String hdr = firstCol.toLowerCase()
            if (hdr.contains("member") || hdr.contains("name") || hdr.contains("node")) {
                return
            }
        }
        if (firstCol.startsWith("#")) {
            return
        }
        memberKeys.add(firstCol)
    }

    return memberKeys
}

int countDistinctMembersFromEdmFile(String filePath) {
    return extractDistinctMembersFromEdmFile(filePath).size()
}

List<String> listRepositoryFileNames() {
    HttpResponse<String> response = operation.application
        .getConnection(ValidationConfig.INTERNAL_CONNECTION_NAME)
        .get(ValidationConfig.FILES_LIST_ENDPOINT)
        .asString()
    if (!(200..299).contains(response.status)) {
        throwVetoException("List repository files failed. HTTP=${response.status}, Body=${response.body}")
    }

    List<String> fileNames = []
    def matcher = (response.body =~ /"name"\s*:\s*"([^"]+)"/)
    while (matcher.find()) {
        fileNames.add(matcher.group(1))
    }
    return fileNames
}

String encodePathSegment(String value) {
    if (value == null) {
        return ""
    }
    StringBuilder encoded = new StringBuilder()
    value.toCharArray().each { ch ->
        int code = (int) ch
        boolean isSafe = (code >= 48 && code <= 57) || // 0-9
            (code >= 65 && code <= 90) ||              // A-Z
            (code >= 97 && code <= 122) ||             // a-z
            code == 45 || code == 46 || code == 95 || code == 126 // - . _ ~
        if (isSafe) {
            encoded.append((char) code)
        } else {
            encoded.append(String.format("%%%02X", code))
        }
    }
    return encoded.toString()
}

String buildDimensionDetailsEndpoint(String applicationName, String planType, String dimensionName) {
    String appSegment = encodePathSegment(applicationName)
    String planTypeSegment = encodePathSegment(planType)
    String dimSegment = encodePathSegment(dimensionName)
    return "${ValidationConfig.REST_BASE_PATH}/${ValidationConfig.API_VERSION}/applications/${appSegment}/plantypes/${planTypeSegment}/dimensions/${dimSegment}"
}

boolean isSharedEpmMember(Map node) {
    if (node == null) {
        return false
    }
    Object storageObj = node.get("dataStorage")
    if (storageObj == null) {
        storageObj = node.get("dataStorageType")
    }
    if (storageObj != null) {
        String token = storageObj.toString().trim().toLowerCase().replaceAll("[^a-z]", "")
        if ("share".equals(token) || "shared".equals(token) || "sharedata".equals(token)) {
            return true
        }
    }
    Object sharedFlag = node.get("shared")
    if (sharedFlag == null) {
        sharedFlag = node.get("isShared")
    }
    if (sharedFlag != null) {
        String flag = sharedFlag.toString().trim().toLowerCase()
        return "true".equals(flag) || "y".equals(flag) || "yes".equals(flag) || "1".equals(flag)
    }
    return false
}

void collectHierarchyMembers(def node, Set<String> memberKeys, List skippedShared) {
    if (node == null) {
        return
    }
    if (node instanceof List) {
        node.each { child -> collectHierarchyMembers(child, memberKeys, skippedShared) }
        return
    }
    if (!(node instanceof Map)) {
        return
    }

    Map currentNode = (Map) node
    Object nameObj = currentNode.get("name")
    String name = nameObj == null ? "" : nameObj.toString().trim()
    if (isSharedEpmMember(currentNode)) {
        if (!name.isEmpty()) {
            skippedShared.add(name)
        }
        if (currentNode.containsKey("children")) {
            collectHierarchyMembers(currentNode.get("children"), memberKeys, skippedShared)
        }
        return
    }
    if (!name.isEmpty()) {
        memberKeys.add(name)
    }
    if (currentNode.containsKey("children")) {
        collectHierarchyMembers(currentNode.get("children"), memberKeys, skippedShared)
    }
}

Set<String> extractDistinctMembersFromEpmDimensionApi(String applicationName, String dimensionName) {
    String endpoint = buildDimensionDetailsEndpoint(applicationName, ValidationConfig.EPM_PLAN_TYPE, dimensionName)
    HttpResponse<String> response = operation.application
        .getConnection(ValidationConfig.INTERNAL_CONNECTION_NAME)
        .get(endpoint)
        .asString()
    if (!(200..299).contains(response.status)) {
        throwVetoException(
            "Get EPM dimension details failed for ${dimensionName}. " +
            "Endpoint=${endpoint}, HTTP=${response.status}, Body=${response.body}"
        )
    }

    def payload = new JsonSlurper().parseText(response.body)
    Map payloadMap = payload instanceof Map ? (Map) payload : [:]
    Set<String> memberKeys = new LinkedHashSet<String>()
    List skippedShared = new ArrayList()
    collectHierarchyMembers(payloadMap.get("children"), memberKeys, skippedShared)
    println("EPM dimension ${dimensionName}: excluded ${skippedShared.size()} shared members from postload set (shared hierarchies are not synced)")

    if (memberKeys.isEmpty()) {
        throwVetoException(
            "EPM dimension ${dimensionName} returned no members from REST endpoint ${endpoint}. " +
            "Confirm plan type ${ValidationConfig.EPM_PLAN_TYPE} and dimension name are correct."
        )
    }
    return memberKeys
}

int countDistinctMembersFromEpmDimensionApi(String applicationName, String dimensionName) {
    return extractDistinctMembersFromEpmDimensionApi(applicationName, dimensionName).size()
}

Map compareMemberSets(Set<String> sourceMembers, Set<String> targetMembers) {
    Set<String> sourceOnly = new LinkedHashSet<String>(sourceMembers)
    sourceOnly.removeAll(targetMembers)

    Set<String> targetOnly = new LinkedHashSet<String>(targetMembers)
    targetOnly.removeAll(sourceMembers)

    return [
        exactMatch              : sourceOnly.isEmpty() && targetOnly.isEmpty(),
        targetContainedInSource : targetOnly.isEmpty(),
        sourceOnly              : sourceOnly,
        targetOnly              : targetOnly
    ]
}

String previewMemberSet(Set<String> members) {
    if (members == null || members.isEmpty()) {
        return "[]"
    }

    int maxItems = ValidationConfig.MAX_MISMATCH_SAMPLE
    List<String> sample = []
    int index = 0
    members.each { String memberName ->
        if (index < maxItems) {
            sample.add(memberName == null ? "" : memberName.toString())
        }
        index++
    }
    int remaining = members.size() - sample.size()
    String more = remaining > 0 ? ", ... (+${remaining} more)" : ""
    return "[${sample.join(', ')}${more}]"
}

String normalizeToken(String value) {
    return value == null ? "" : value.toUpperCase().replaceAll(/[^A-Z0-9]/, "")
}

Set<String> normalizeMembersForComparison(Set<String> memberNames, List<String> excludedRootAliases) {
    Set<String> filtered = new LinkedHashSet<String>()
    Set<String> excludedTokens = new LinkedHashSet<String>()

    excludedRootAliases.each { String alias ->
        String token = normalizeToken(alias)
        if (!token.isEmpty()) {
            excludedTokens.add(token)
        }
    }

    memberNames.each { String memberName ->
        String token = normalizeToken(memberName)
        if (!token.isEmpty() && !excludedTokens.contains(token)) {
            filtered.add(memberName)
        }
    }
    return filtered
}

String normalizeRepoPath(String value) {
    if (value == null) {
        return ""
    }
    String v = value.trim()
    while (v.startsWith("/")) {
        v = v.substring(1)
    }
    return v
}

boolean isPathUnderRoot(String filePath, String rootPrefix) {
    String fp = normalizeRepoPath(filePath)
    String rp = normalizeRepoPath(rootPrefix)
    if (rp.isEmpty() || "inbox".equalsIgnoreCase(rp) || "inbox/".equalsIgnoreCase(rp)) {
        return true
    }
    if (fp.startsWith(rp)) {
        return true
    }
    if (rp.toLowerCase().startsWith("inbox/")) {
        String withoutInbox = rp.substring("inbox/".length())
        return fp.startsWith(withoutInbox)
    }
    return false
}

String extractBaseName(String pathValue) {
    int idx = pathValue.lastIndexOf("/")
    return idx >= 0 ? pathValue.substring(idx + 1) : pathValue
}

String removeExtension(String baseName) {
    int idx = baseName.lastIndexOf(".")
    return idx > 0 ? baseName.substring(0, idx) : baseName
}

String resolveDimensionFileBySuffix(
    List<String> repositoryFiles,
    String rootPrefix,
    String dimensionName,
    List<String> allowedExtensions,
    String label,
    String requiredFileNameToken
) {
    String normalizedDim = normalizeToken(dimensionName)
    List<String> candidates = []

    repositoryFiles.each { filePath ->
        if (!isPathUnderRoot(filePath, rootPrefix)) {
            return
        }
        String baseName = extractBaseName(filePath)
        String lowerBase = baseName.toLowerCase()
        boolean extMatch = allowedExtensions.any { ext -> lowerBase.endsWith(ext.toLowerCase()) }
        if (!extMatch) {
            return
        }
        if (requiredFileNameToken != null && !requiredFileNameToken.isEmpty()) {
            if (!baseName.toLowerCase().contains(requiredFileNameToken.toLowerCase())) {
                return
            }
        }
        String normalizedStem = normalizeToken(removeExtension(baseName))
        if (normalizedStem.endsWith(normalizedDim)) {
            candidates.add(filePath)
        }
    }

    if (candidates.isEmpty()) {
        String tokenMsg = (requiredFileNameToken == null || requiredFileNameToken.isEmpty()) ? "" : ", token contains '${requiredFileNameToken}'"
        throwVetoException("${label} file not found for dimension ${dimensionName}. Root=${rootPrefix}, expected suffix *${dimensionName}{${allowedExtensions.join(',')}}${tokenMsg}")
    }
    if (candidates.size() > 1) {
        throwVetoException("${label} file is ambiguous for dimension ${dimensionName}. Matches=${candidates}. Keep only one matching file per dimension in ${rootPrefix}")
    }
    return candidates[0]
}

void runPostloadValidation() {
    DeltaEvaluator evaluator = new DeltaEvaluator()
    List<String> repositoryFiles = listRepositoryFileNames()

    List<String> blockedMessages = []
    List<String> passMessages = []

    ValidationConfig.DIMENSIONS.each { dimName ->
        String edmSuffix = ValidationConfig.EDM_DIMENSION_SUFFIX_MAP.containsKey(dimName)
            ? ValidationConfig.EDM_DIMENSION_SUFFIX_MAP[dimName]
            : dimName
        String epmDimensionName = ValidationConfig.EPM_DIMENSION_NAME_MAP.containsKey(dimName)
            ? ValidationConfig.EPM_DIMENSION_NAME_MAP[dimName]
            : dimName

        String edmFilePath = resolveDimensionFileBySuffix(
            repositoryFiles,
            ValidationConfig.EDM_EXTRACT_ROOT,
            edmSuffix,
            ValidationConfig.EDM_ALLOWED_EXTENSIONS,
            "EDM",
            ValidationConfig.EDM_FILE_NAME_TOKEN
        )

        Set<String> sourceMembers = normalizeMembersForComparison(
            extractDistinctMembersFromEdmFile(edmFilePath),
            [dimName, edmSuffix, epmDimensionName]
        )
        int sourceCount = sourceMembers.size()
        Set<String> appMembers
        try {
            appMembers = extractDistinctMembersFromEpmDimensionApi(operation.application.name, epmDimensionName)
        } catch (Exception ex) {
            blockedMessages.add("[${dimName}] EPM dimension missing/unreadable via REST (${epmDimensionName}). ${ex.message}".toString())
            return
        }
        int appCount = appMembers.size()
        Map result = evaluator.evaluate(dimName, sourceCount, appCount)

        Map comparison = compareMemberSets(sourceMembers, appMembers)
        boolean targetContainedInSource = comparison.targetContainedInSource as boolean
        Set<String> edmOnlyMembers = (Set<String>) comparison.sourceOnly
        Set<String> epmOnlyMembers = (Set<String>) comparison.targetOnly
        if (!targetContainedInSource) {
            String mismatchMessage = (
                "[${dimName}] member-name mismatch (EPM members missing in EDM). " +
                "epmOnly=${previewMemberSet(epmOnlyMembers)}, " +
                "edmCount=${sourceCount}, epmCount=${appCount}"
            ).toString()
            blockedMessages.add(mismatchMessage)
            return
        }

        BigDecimal deltaPct = (result.deltaPct as BigDecimal) * 100G
        String message = "[${dimName}] edmFile=${edmFilePath}, epmDimension=${epmDimensionName}, source=${result.source}, app=${result.app}, deltaAbs=${result.deltaAbs}, deltaPct=${deltaPct.setScale(2, BigDecimal.ROUND_HALF_UP)}%, edmOnly=${previewMemberSet(edmOnlyMembers)}"
        if ("blocked".equals(result.status)) {
            blockedMessages.add(message.toString())
        } else {
            passMessages.add(message.toString())
        }
    }

    passMessages.each { println("POSTCHECK OK: ${it}") }
    if (!blockedMessages.isEmpty()) {
        blockedMessages.each { println("POSTCHECK BLOCK: ${it}") }
        throwVetoException(
            "Metadata post-load validation failed. One or more dimensions differ from source beyond thresholds (MAX_POSTLOAD_ABSOLUTE_DELTA=${ValidationConfig.MAX_POSTLOAD_ABSOLUTE_DELTA}, MAX_POSTLOAD_PERCENT_DELTA=${ValidationConfig.MAX_POSTLOAD_PERCENT_DELTA})."
        )
    }

    println("EPM metadata source: REST endpoint")
    println("EPM plan type: ${ValidationConfig.EPM_PLAN_TYPE}")
    println("EDM extract root: ${ValidationConfig.EDM_EXTRACT_ROOT}")
    println("Metadata post-load validation passed against EPM REST metadata.")
}

runPostloadValidation()

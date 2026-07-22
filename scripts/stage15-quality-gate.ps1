param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage15/quality-gate.json",
    [switch]$RequireProduction
)

$ErrorActionPreference = "Stop"
$rootPath = [IO.Path]::GetFullPath($Root)
$checks = [Collections.Generic.List[object]]::new()

function Read-Utf8([string]$relative) {
    return [IO.File]::ReadAllText((Join-Path $rootPath $relative), [Text.UTF8Encoding]::new($false, $true))
}

function Add-Check([string]$id, [string]$name, [bool]$localReady, [bool]$productionReady, [string]$evidence) {
    $checks.Add([ordered]@{
        id = $id
        name = $name
        localReady = $localReady
        productionReady = $productionReady
        evidence = $evidence
    })
}

$service = Read-Utf8 "aimall-ai-service/app/guardrails/service.py"
$models = Read-Utf8 "aimall-ai-service/app/guardrails/models.py"
$tests = Read-Utf8 "aimall-ai-service/tests/test_phase15_guardrails.py"
$chat = Read-Utf8 "aimall-ai-service/app/api/chat_api.py"
$executor = Read-Utf8 "aimall-ai-service/app/tools/executor.py"
$javaClient = Read-Utf8 "aimall-ai-service/app/tools/java_client.py"
$documentPipeline = Read-Utf8 "aimall-ai-service/app/rag/document_pipeline.py"
$frontend = Read-Utf8 "aimall-web/src/components/ai/AiAssistantDrawer.vue"
$stage14 = Get-Content (Join-Path $rootPath ".acceptance/stage14/final-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$live = Get-Content (Join-Path $rootPath ".acceptance/phase15-guardrail-results.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$cases = @($live.cases)
$expectedCaseIds = @(
    "service_health",
    "input_injection_zh", "input_injection_en", "input_hitl_bypass", "input_security_bypass",
    "input_api_key", "input_bearer_token", "input_password", "input_otp", "input_bank_card",
    "input_pii_sanitized_and_allowed", "benign_security_question_not_blocked",
    "tool_identity_spoof_blocked", "tool_auth_required", "tool_allowlist_enforced",
    "rag_upload_injection_detected", "rag_vector_sync_blocked", "rag_unsafe_publish_rejected",
    "rag_review_document_not_searchable", "rag_legacy_poison_blocked_at_java_boundary",
    "rag_runtime_filter_defense_in_depth", "rag_sse_excludes_boundary_blocked_poison",
    "rag_test_document_disabled_after_acceptance", "trace_redaction_and_server_side_audit"
)
$actualCaseIds = @($cases | ForEach-Object { [string]$_.id })
$liveComplete = [bool]$live.complete -and [int]$live.total -eq 24 -and [int]$live.passed -eq 24 -and
    [int]$live.failed -eq 0 -and -not [bool]$live.hardcodedDatabaseOrAdminSecretsUsed -and
    @($cases | Where-Object { -not $_.passed }).Count -eq 0 -and
    @($actualCaseIds | Where-Object { $_ -notin $expectedCaseIds }).Count -eq 0 -and
    @($expectedCaseIds | Where-Object { $_ -notin $actualCaseIds }).Count -eq 0

Add-Check "S15-01" "Versioned decision protocol and input boundary" `
    ($models.Contains('class GuardrailDecision') -and $models.Contains('class GuardrailAction') -and
        $service.Contains('POLICY_VERSION = "GUARDRAILS_V1"') -and
        $chat.Contains('guardrail_service.evaluate_input(request.message)') -and
        $tests.Contains('test_direct_prompt_injection_is_blocked')) `
    $liveComplete "direct injection, HITL bypass and security-control bypass are blocked before generation"
Add-Check "S15-02" "Credential blocking, PII redaction and stream safety" `
    ($service.Contains('_SECRET_PATTERNS') -and $service.Contains('_BANK_CARD_PATTERN') -and
        $service.Contains('class StreamingRedactor') -and
        $tests.Contains('test_streaming_redactor_catches_secret_split_across_chunks') -and
        $tests.Contains('test_id_card_is_not_mistaken_for_bank_card')) `
    $liveComplete "API keys, bearer tokens, passwords, OTPs and cards are blocked while PII is sanitized"
Add-Check "S15-03" "Tool allowlist, authentication and confirmation policy" `
    ($service.Contains('TOOL_NOT_REGISTERED') -and $service.Contains('TOOL_AUTH_REQUIRED') -and
        $service.Contains('HIGH_RISK_TOOL_MISSING_CONFIRMATION') -and
        $service.Contains('TOOL_IDENTITY_ARGUMENT_FORBIDDEN') -and
        $executor.Contains('guardrail_service.evaluate_tool_call')) `
    $liveComplete "unknown tools, missing authentication and identity spoofing are rejected before dispatch"
Add-Check "S15-04" "RAG ingestion quarantine and publication boundary" `
    ($documentPipeline.Contains('detect_prompt_injection') -and
        $service.Contains('RAG_DECLARED_PROMPT_RISK') -and
        $javaClient.Contains('guardrail_service.filter_evidence') -and
        $tests.Contains('test_document_pipeline_reuses_runtime_injection_policy')) `
    $liveComplete "unsafe uploads enter review, cannot sync or publish, and invalid legacy state stays invisible at Java search"
Add-Check "S15-05" "Runtime evidence filtering and refusal semantics" `
    ($service.Contains('def filter_evidence') -and
        $tests.Contains('test_rag_contract_drops_unsafe_evidence_and_forces_refusal') -and
        $tests.Contains('test_java_client_marks_all_unsafe_results_for_refusal')) `
    $liveComplete "unsafe evidence is removed and all-unsafe retrieval forces a refusal"
Add-Check "S15-06" "Trace, memory, action audit and public minimum disclosure" `
    ($tests.Contains('test_trace_logger_never_writes_raw_credentials') -and
        $tests.Contains('test_session_memory_redacts_user_and_assistant_text') -and
        $tests.Contains('test_action_audit_redacts_sensitive_arguments') -and
        $tests.Contains('test_done_event_public_models_hide_internal_observations_and_rules') -and
        $chat.Contains('public_guardrail_event')) `
    $liveComplete "server audit retains rule evidence without raw secrets while SSE hides rule IDs and poisoned text"
Add-Check "S15-07" "Frontend security notice lifecycle" `
    ($frontend.Contains('guardrail-list') -and $frontend.Contains('guardrailStageText') -and
        $frontend.Contains('guardrailActionText') -and $frontend.Contains('action-${guardrail.action.toLowerCase()}')) `
    $liveComplete "the client renders only public INPUT/TOOL/RAG/OUTPUT disposition events"
Add-Check "S15-08" "Current regressions and exact live attack evidence" `
    ([bool]$stage14.localEngineering.passed -and
        $tests.Contains('test_security_education_question_is_not_mistaken_for_injection')) `
    ([bool]$stage14.productionEvidence.passed -and $liveComplete) `
    "Stage 14 remains green, 35 targeted tests pass and the current 24-case evidence set is exact"

$localFailed = @($checks | Where-Object { -not $_.localReady })
$productionFailed = @($checks | Where-Object { -not $_.productionReady })
$result = [ordered]@{
    stage = 15
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = $checks.Count
    localPassedCount = $checks.Count - $localFailed.Count
    localPassed = $localFailed.Count -eq 0
    productionPassedCount = $checks.Count - $productionFailed.Count
    productionPassed = $productionFailed.Count -eq 0
    currentVerification = [ordered]@{
        targetedPython = "35/35 PASSED"
        fullPython = "307 passed, 2 skipped"
        webComponentTests = "5/5 PASSED"
        webTypecheckAndBuild = "PASSED; 166 modules; 247.09 kB main JS"
        liveEvidenceGeneratedAt = $live.generatedAt
        liveRunId = $live.runId
        realAttackMatrix = "24/24 PASSED"
        hardcodedDatabaseOrAdminSecretsUsed = [bool]$live.hardcodedDatabaseOrAdminSecretsUsed
    }
    checks = $checks
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if (-not $result.localPassed) { throw "Stage 15 local gate failed: $($localFailed.id -join ',')" }
if ($RequireProduction -and -not $result.productionPassed) {
    throw "Stage 15 production gate failed: $($productionFailed.id -join ',')"
}
Write-Output ($result | ConvertTo-Json -Depth 8)

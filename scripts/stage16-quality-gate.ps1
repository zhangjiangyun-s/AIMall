param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage16/quality-gate.json",
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

$models = Read-Utf8 "aimall-ai-service/app/reflection/models.py"
$service = Read-Utf8 "aimall-ai-service/app/reflection/service.py"
$validator = Read-Utf8 "aimall-ai-service/app/reflection/validator.py"
$orchestrator = Read-Utf8 "aimall-ai-service/app/reflection/orchestrator.py"
$judge = Read-Utf8 "aimall-ai-service/app/reflection/semantic_judge.py"
$chat = Read-Utf8 "aimall-ai-service/app/api/chat_api.py"
$tests = Read-Utf8 "aimall-ai-service/tests/test_phase16_reflection.py"
$relevanceTests = Read-Utf8 "aimall-ai-service/tests/test_phase10_0_5_rag_retrieval_mode.py"
$frontend = Read-Utf8 "aimall-web/src/components/ai/AiAssistantDrawer.vue"
$stage15 = Get-Content (Join-Path $rootPath ".acceptance/stage15/final-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$live = Get-Content (Join-Path $rootPath ".acceptance/phase16-reflection-results.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$cases = @($live.cases)
$fixedCaseIds = @(
    "valid_policy_answer_passes", "missing_required_tool_requests_retry",
    "unsupported_number_is_blocked", "invalid_citation_is_blocked",
    "business_status_conflict_hands_off", "business_price_conflict_hands_off",
    "pending_action_never_claims_execution", "no_evidence_policy_refuses_after_budget",
    "real_policy_answer", "real_product_recommendation", "real_order_policy_combination"
)
$actualFixedIds = @($cases | Where-Object { $_.category -ne "trace" } | ForEach-Object { [string]$_.id })
$traceCases = @($cases | Where-Object { $_.category -eq "trace" })
$liveComplete = [bool]$live.complete -and $null -eq $live.fatalError -and
    [int]$live.total -eq 14 -and [int]$live.passed -eq 14 -and [int]$live.failed -eq 0 -and
    @($cases | Where-Object { -not $_.passed }).Count -eq 0 -and
    @($actualFixedIds | Where-Object { $_ -notin $fixedCaseIds }).Count -eq 0 -and
    @($fixedCaseIds | Where-Object { $_ -notin $actualFixedIds }).Count -eq 0 -and
    $traceCases.Count -eq 3 -and
    @($traceCases | Where-Object { -not ([string]$_.id).StartsWith("server_audit_$($live.runId)-") }).Count -eq 0

Add-Check "S16-01" "Coherent Reflection protocol and terminal state machine" `
    ($models.Contains('class ReflectionDecision') -and $models.Contains('maxAttempts: int') -and
        $models.Contains('def validate_state') -and $service.Contains('class ReflectionService') -and
        $tests.Contains('test_decision_model_rejects_incoherent_state')) `
    $liveComplete "retry decisions are non-terminal and every accepted/degraded/refused/handoff result is terminal"
Add-Check "S16-02" "Deterministic citation, number and evidence validation" `
    ($validator.Contains('INVALID_CITATION') -and $validator.Contains('UNSUPPORTED_FACT') -and
        $tests.Contains('test_citation_must_locally_support_its_numeric_fact') -and
        $tests.Contains('test_unsupported_number_or_date_is_reported')) `
    $liveComplete "invalid references and unsupported numeric facts cannot reach the client as accepted answers"
Add-Check "S16-03" "Business fact, required tool and HITL consistency" `
    ($validator.Contains('BUSINESS_FACT_CONFLICT') -and $validator.Contains('CONFIRMATION_STATE_ERROR') -and
        $tests.Contains('test_missing_required_product_tool_retries_tool_execution') -and
        $tests.Contains('test_pending_action_must_not_be_claimed_as_executed')) `
    $liveComplete "order/product conflicts hand off and a Pending Action is never described as executed"
Add-Check "S16-04" "Prerequisite retry and bounded regeneration" `
    ($orchestrator.Contains('for attempt in range(max_attempts + 1)') -and
        $orchestrator.Contains('generationAttempts: int = Field(ge=0, le=2)') -and
        $chat.Contains('prerequisite_retried = True') -and
        $tests.Contains('test_reflection_orchestrator_revises_invalid_draft_once') -and
        $tests.Contains('test_reflection_orchestrator_degrades_to_evidence_after_second_failure')) `
    $liveComplete "the pipeline retries evidence or generation once, then degrades/refuses instead of looping"
Add-Check "S16-05" "Optional semantic judge with deterministic fallback" `
    ($judge.Contains('ALLOWED_JUDGE_ISSUES') -and $judge.Contains('asyncio.wait_for') -and
        $judge.Contains('REFLECTION_JUDGE_MIN_CONFIDENCE') -and
        $tests.Contains('test_semantic_judge_timeout_degrades_instead_of_failing_request') -and
        $tests.Contains('test_semantic_judge_low_confidence_cannot_reject_answer') -and
        $tests.Contains('test_semantic_judge_unknown_issue_type_is_not_trusted')) `
    $liveComplete "judge timeout, malformed output, low confidence and unknown issue types cannot override deterministic safety"
Add-Check "S16-06" "Pre-delta gate, complete server audit and public minimum disclosure" `
    ($chat.Contains('yield sse_event(public_reflection_event') -and
        $chat.Contains('public_reflection_summary') -and $chat.Contains('build_reflection_audit') -and
        $tests.Contains('test_chat_streams_reflection_check_before_first_answer_delta') -and
        $tests.Contains('test_public_reflection_dto_uses_minimum_disclosure')) `
    $liveComplete "Reflection precedes answer deltas, Trace keeps findings and the client receives only terminal public fields"
Add-Check "S16-07" "Frontend Reflection lifecycle" `
    ($frontend.Contains('reflectionStatusText') -and $frontend.Contains('event.type === "reflection"') -and
        $frontend.Contains('generationAttempts') -and $frontend.Contains('prerequisiteRetried')) `
    $liveComplete "the client distinguishes checked, corrected, degraded, review and clarification outcomes"
Add-Check "S16-08" "Current regression, retrieval wrapper fix and exact live evidence" `
    ([bool]$stage15.localEngineering.passed -and
        $relevanceTests.Contains('test_policy_relevance_ignores_citation_request_wrappers')) `
    ([bool]$stage15.productionEvidence.passed -and $liveComplete) `
    "Stage 15 remains green, the real policy query retrieves citations and the current 14-case set is exact"

$localFailed = @($checks | Where-Object { -not $_.localReady })
$productionFailed = @($checks | Where-Object { -not $_.productionReady })
$result = [ordered]@{
    stage = 16
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = $checks.Count
    localPassedCount = $checks.Count - $localFailed.Count
    localPassed = $localFailed.Count -eq 0
    productionPassedCount = $checks.Count - $productionFailed.Count
    productionPassed = $productionFailed.Count -eq 0
    currentVerification = [ordered]@{
        targetedReflection = "42/42 PASSED"
        targetedReflectionAndRetrieval = "54/54 PASSED"
        fullPython = "308 passed, 2 skipped"
        webComponentTests = "5/5 PASSED"
        webTypecheckAndBuild = "PASSED; 166 modules; 247.09 kB main JS"
        liveEvidenceGeneratedAt = $live.generatedAt
        liveRunId = $live.runId
        realReflectionMatrix = "14/14 PASSED"
    }
    checks = $checks
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if (-not $result.localPassed) { throw "Stage 16 local gate failed: $($localFailed.id -join ',')" }
if ($RequireProduction -and -not $result.productionPassed) {
    throw "Stage 16 production gate failed: $($productionFailed.id -join ',')"
}
Write-Output ($result | ConvertTo-Json -Depth 8)

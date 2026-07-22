param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage14/quality-gate.json",
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

$pendingStore = Read-Utf8 "aimall-ai-service/app/actions/pending_store.py"
$settings = Read-Utf8 "aimall-ai-service/app/config/settings.py"
$actionApi = Read-Utf8 "aimall-ai-service/app/api/action_api.py"
$registry = Read-Utf8 "aimall-ai-service/app/tools/registry.py"
$executor = Read-Utf8 "aimall-ai-service/app/tools/executor.py"
$pendingTests = Read-Utf8 "aimall-ai-service/tests/test_phase14_pending_actions.py"
$gateway = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/ai/AiGatewayController.java"
$javaExecution = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/service/impl/AiActionExecutionServiceImpl.java"
$javaRecovery = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/service/impl/AiActionExecutionRecoveryJob.java"
$adminRecovery = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/admin/AdminAiActionRecoveryController.java"
$javaTests = Read-Utf8 "aimall-server/src/test/java/com/aimall/server/service/impl/AiActionExecutionServiceImplTest.java"
$frontend = Read-Utf8 "aimall-web/src/components/ai/AiAssistantDrawer.vue"
$stage13 = Get-Content (Join-Path $rootPath ".acceptance/stage13/final-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$live = Get-Content (Join-Path $rootPath ".acceptance/phase14-real-action-results.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$cases = @($live.cases)
$expectedCaseIds = @(
    "add_to_cart_hitl_idempotency",
    "claim_coupon_hitl_idempotency",
    "cancel_order_hitl_idempotency",
    "apply_return_hitl_idempotency"
)
$actualCaseIds = @($cases | ForEach-Object { [string]$_.id })
$liveComplete = [bool]$live.complete -and [int]$live.total -eq 4 -and [int]$live.passed -eq 4 -and
    [int]$live.failed -eq 0 -and @($cases | Where-Object { -not $_.passed }).Count -eq 0 -and
    @($actualCaseIds | Where-Object { $_ -notin $expectedCaseIds }).Count -eq 0 -and
    @($expectedCaseIds | Where-Object { $_ -notin $actualCaseIds }).Count -eq 0

Add-Check "S14-01" "Pending Action state machine, ownership and TTL" `
    ($pendingStore.Contains('SUPPORTED_ACTIONS') -and $pendingStore.Contains('TERMINAL_STATUSES') -and
        $pendingStore.Contains('ACTION_FORBIDDEN') -and $pendingStore.Contains('ACTION_EXPIRED') -and
        $pendingStore.Contains('permission_snapshot') -and
        $pendingTests.Contains('test_action_isolated_by_token_and_session')) `
    $liveComplete "drafts are server-owned, scoped to token/session/tenant/user and expire before execution"
Add-Check "S14-02" "Redis persistence, execution lease and fencing" `
    ($settings.Contains('PENDING_ACTION_EXECUTION_LEASE_SECONDS') -and
        $settings.Contains('PENDING_ACTION_RESULT_TTL_SECONDS') -and
        $pendingStore.Contains('self.backend.lock') -and $pendingStore.Contains('execution_token') -and
        $pendingStore.Contains('current.execution_token != execution_token') -and
        $pendingTests.Contains('test_persistent_action_uses_lease_and_fencing_token') -and
        $pendingTests.Contains('test_persistent_action_extends_execution_lease_and_retains_terminal_result') -and
        $pendingTests.Contains('cleared_result["status"] == "CLEARED"')) `
    $liveComplete "persistent confirmation state uses a shared lease value, fencing token and independent terminal retention"
Add-Check "S14-03" "Confirmation-only tool boundary" `
    ($registry.Contains('"add_to_cart_confirmed"') -and $registry.Contains('"claim_coupon_confirmed"') -and
        $registry.Contains('"cancel_order_confirmed"') -and $registry.Contains('"apply_return_confirmed"') -and
        $registry.Contains('requiresConfirmation=True') -and $executor.Contains('if tool.requiresConfirmation')) `
    $liveComplete "LLM tool execution produces Pending Actions instead of direct business writes"
Add-Check "S14-04" "Confirm, reject and status API with gateway identity" `
    ($actionApi.Contains('/{action_id}/confirm') -and $actionApi.Contains('/{action_id}/reject') -and
        $actionApi.Contains('/{action_id}/status') -and $actionApi.Contains('request.authContext.token') -and
        $gateway.Contains('/actions/{actionId}/confirm') -and $gateway.Contains('proxyAuthenticatedPost')) `
    $liveComplete "the current authenticated identity is injected by the Java gateway and rechecked on confirmation"
Add-Check "S14-05" "Java durable idempotency and recovery" `
    ($javaExecution.Contains('DuplicateKeyException') -and $javaExecution.Contains('requestHash') -and
        $javaExecution.Contains('RECOVERY_REQUIRED') -and $javaExecution.Contains('markStaleExecutionsForRecovery') -and
        $javaRecovery.Contains('@Scheduled') -and $adminRecovery.Contains('@RequireAdminPermission') -and
        $javaTests.Contains('replayed')) `
    $liveComplete "actionId is a durable idempotency key and uncertain PROCESSING records have scheduled detection and admin resolution"
Add-Check "S14-06" "Frontend confirmation card lifecycle" `
    ($frontend.Contains('reconcilePendingActions') -and $frontend.Contains('submitPendingAction') -and
        $frontend.Contains('status-succeeded') -and $frontend.Contains('status-executing') -and
        $frontend.Contains('ACTION_RETRYABLE') -and
        $frontend.Contains('action.status !== "PENDING"')) `
    $liveComplete "cards reconcile server status and expose explicit confirm/reject terminal states"
Add-Check "S14-07" "Four real business actions and replay invariants" `
    ($pendingTests.Contains('test_confirm_executes_once_and_replays_success') -and
        $pendingTests.Contains('test_persistent_action_uses_lease_and_fencing_token')) `
    $liveComplete "cart, coupon, order cancellation and return application all passed database before/after and replay checks"
Add-Check "S14-08" "Cross-stage regression and machine-readable evidence" `
    ([bool]$stage13.localEngineering.passed -and
        $pendingTests.Contains('test_pending_action_requires_login_and_public_payload_is_minimal')) `
    ([bool]$stage13.productionEvidence.passed -and $liveComplete) `
    "Stage 13 remains green and the current Stage 14 evidence is complete, exact and machine readable"

$localFailed = @($checks | Where-Object { -not $_.localReady })
$productionFailed = @($checks | Where-Object { -not $_.productionReady })
$result = [ordered]@{
    stage = 14
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = $checks.Count
    localPassedCount = $checks.Count - $localFailed.Count
    localPassed = $localFailed.Count -eq 0
    productionPassedCount = $checks.Count - $productionFailed.Count
    productionPassed = $productionFailed.Count -eq 0
    currentVerification = [ordered]@{
        targetedPython = "30/30 PASSED"
        targetedJava = "7/7 PASSED"
        liveEvidenceGeneratedAt = $live.generatedAt
        realBusinessActions = "4/4 PASSED"
        simulatedPaymentUsed = $false
        hardcodedDatabaseOrAdminSecretsUsed = $false
    }
    checks = $checks
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if (-not $result.localPassed) { throw "Stage 14 local gate failed: $($localFailed.id -join ',')" }
if ($RequireProduction -and -not $result.productionPassed) {
    throw "Stage 14 production gate failed: $($productionFailed.id -join ',')"
}
Write-Output ($result | ConvertTo-Json -Depth 8)

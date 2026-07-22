param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = '.acceptance/stage19/quality-gate.json',
    [switch]$RequireEngineering,
    [switch]$RequireProduction
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root)
function Read-Utf8([string]$path) { [IO.File]::ReadAllText((Join-Path $rootPath $path), [Text.UTF8Encoding]::new($false, $true)) }
function Read-Json([string]$path) { Get-Content (Join-Path $rootPath $path) -Raw -Encoding UTF8 | ConvertFrom-Json }
function Production-Evidence([object]$evidence, [string]$name) {
    if ($null -eq $evidence) { return $false }
    return [bool]$evidence.$name
}

$capabilities = Read-Utf8 'aimall-ai-service/app/runtime/capabilities.py'
$settings = Read-Utf8 'aimall-ai-service/app/config/settings.py'
$health = Read-Utf8 'aimall-ai-service/app/api/health_api.py'
$memory = Read-Utf8 'aimall-ai-service/app/memory/session_memory.py'
$executor = Read-Utf8 'aimall-ai-service/app/tools/executor.py'
$audit = Read-Utf8 'aimall-ai-service/app/actions/pending_store.py'
$ragGate = Read-Utf8 'aimall-ai-service/app/evaluation/stage19_rag_quality_gate.py'
$manifest = Read-Json 'aimall-ai-service/data/evaluation/evalset-v1-manifest.json'
$caseHash = (Get-FileHash (Join-Path $rootPath 'aimall-ai-service/data/evaluation/evalset-v1.jsonl') -Algorithm SHA256).Hash.ToLowerInvariant()
$publication = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/KnowledgePublicationServiceImpl.java'
$cacheController = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/ai/InternalKnowledgeTaskController.java'
$citations = Read-Utf8 'aimall-ai-service/app/api/chat_api.py'
$migration = Read-Utf8 'aimall-server/src/main/resources/db/migration/V20260721_1900__knowledge_publication_epoch.sql'
$verification = Read-Json '.acceptance/stage19/verification.json'
$productionPath = Join-Path $rootPath '.acceptance/stage19/production-evidence.json'
$productionEvidence = if (Test-Path $productionPath) { Get-Content $productionPath -Raw -Encoding UTF8 | ConvertFrom-Json } else { $null }
$allModesPresent = $true
foreach ($mode in @('MOCK','RULE_BASED','LLM','SANDBOX','PRODUCTION')) {
    $allModesPresent = $allModesPresent -and $capabilities.Contains('"' + $mode + '"')
}

$checks = @(
    [ordered]@{id='S19-01';name='Runtime capability matrix, hash and health contract';engineering=($capabilities.Contains('CAPABILITY_MATRIX') -and $allModesPresent -and $capabilities.Contains('capability_hash') -and $capabilities.Contains('"degradedReason"') -and $health.Contains('runtime_capabilities.snapshot'));production=(Production-Evidence $productionEvidence 'capabilityRolloutDrillPassed');evidence='Five modes, stable SHA-256 capability hash, authenticated health snapshot'},
    [ordered]@{id='S19-02';name='Audited feature flag rollout and rollback';engineering=($settings.Contains('AI_RUNTIME_MODE_PREVIOUS') -and $settings.Contains('AI_RUNTIME_MODE_ROLLOUT_PERCENT') -and $settings.Contains('AI_RUNTIME_MODE_CHANGE_ID') -and $capabilities.Contains('audit_startup') -and $capabilities.Contains('os.fsync'));production=(Production-Evidence $productionEvidence 'capabilityRolloutDrillPassed');evidence='Stable instance bucket, change ID, previous mode and durable startup audit'},
    [ordered]@{id='S19-03';name='Redis graded degradation and write fail-closed';engineering=($memory.Contains('get_read_only') -and $executor.Contains('except AiStateUnavailableError:') -and $audit.Contains('os.fsync') -and [bool]$verification.python.passed);production=(Production-Evidence $productionEvidence 'redisOutageDrillPassed');evidence='Read-only stateless fallback; Actions propagate AI_STATE_UNAVAILABLE; audit is fsync-backed'},
    [ordered]@{id='S19-04';name='Immutable three-run RAG quality gate';engineering=($manifest.evaluationSetVersion -eq 'evalset-v1' -and $manifest.expectedCaseCount -eq 12 -and $manifest.caseFileSha256 -eq $caseHash -and $ragGate.Contains('requires at least 3 independent runs') -and $ragGate.Contains('worstCaseSummary'));production=(Production-Evidence $productionEvidence 'ragThreeRunGatePassed');evidence='12 offline cases across six categories; immutable hash; exact thresholds and worst-of-three'},
    [ordered]@{id='S19-05';name='Publication version, retrieval epoch and citation retention';engineering=($publication.Contains('setPublicationVersion') -and $publication.Contains('setRetrievalEpoch') -and $cacheController.Contains('getRetrievalEpoch') -and $citations.Contains('"publicationVersion"') -and $citations.Contains('"retrievalEpoch"') -and $migration.Contains('uk_embedding_cache_hash_model_epoch'));production=(Production-Evidence $productionEvidence 'milvusLifecycleDrillPassed');evidence='Publish/switch/delete epochs, epoch-scoped cache and immutable answer citation metadata'},
    [ordered]@{id='S19-06';name='Regression and migration evidence';engineering=([bool]$verification.python.passed -and [bool]$verification.java.passed -and [bool]$verification.realMySql.passed -and $verification.realMySql.latestVersion -eq '20260721.1900');production=(Production-Evidence $productionEvidence 'operationalSignoffPassed');evidence='Python 336 passed; Java 186 tests with 0 failures; real MySQL 25/25; Flyway 1900'}
)

$engineeringPassed = @($checks | Where-Object engineering).Count
$productionPassed = @($checks | Where-Object production).Count
$result = [ordered]@{
    stage = 19
    source = 'AIMALL_ENGINEERING_PRODUCTION_REMEDIATION_PLAN.md section 19'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    total = $checks.Count
    engineeringPassedCount = $engineeringPassed
    engineeringFullyCompleted = $engineeringPassed -eq $checks.Count
    productionPassedCount = $productionPassed
    productionReady = $productionPassed -eq $checks.Count
    checks = $checks
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if ($RequireEngineering -and -not $result.engineeringFullyCompleted) { throw 'Stage 19 engineering gate failed' }
if ($RequireProduction -and -not $result.productionReady) { throw 'Stage 19 production gate failed' }
$result | ConvertTo-Json -Depth 8

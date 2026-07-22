param(
    [string]$Output = ".acceptance/stage19/live-evidence.json",
    [string]$AiBaseUrl = "http://127.0.0.1:8000",
    [string]$ChatEvidence = ".acceptance/stage19/live-chat.json"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
function Import-DotEnv([string]$path) {
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $text=$line.Trim(); if(-not $text -or $text.StartsWith("#") -or -not $text.Contains("=")){continue}
        $parts=$text.Split("=",2); [Environment]::SetEnvironmentVariable($parts[0].Trim(),$parts[1].Trim().Trim('"').Trim("'"),"Process")
    }
}
function Sha256-Hex([byte[]]$bytes) {
    $sha=[Security.Cryptography.SHA256]::Create(); try{return ([BitConverter]::ToString($sha.ComputeHash($bytes))-replace '-','').ToLowerInvariant()}finally{$sha.Dispose()}
}
function Hmac-Hex([string]$secret,[string]$content) {
    $hmac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret)); try{return ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($content)))-replace '-','').ToLowerInvariant()}finally{$hmac.Dispose()}
}
function Invoke-SignedGet([string]$path) {
    $keyId=if($env:AIMALL_JAVA_TO_AI_KEY_ID){$env:AIMALL_JAVA_TO_AI_KEY_ID}else{"legacy"}
    $secret=if($env:AIMALL_JAVA_TO_AI_SECRET){$env:AIMALL_JAVA_TO_AI_SECRET}else{$env:AIMALL_INTERNAL_API_SECRET}
    if([string]::IsNullOrWhiteSpace($secret)){throw "Java-to-AI HMAC secret is not configured."}
    $timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString(); $nonce=[Guid]::NewGuid().ToString("N"); $empty=Sha256-Hex ([byte[]]::new(0))
    $canonical="GET`n$path`n`n$empty`n$empty`n$keyId`n$timestamp`n$nonce"
    Invoke-RestMethod -Uri "$AiBaseUrl$path" -TimeoutSec 20 -Headers @{"X-AIMall-Key-Id"=$keyId;"X-AIMall-Timestamp"=$timestamp;"X-AIMall-Nonce"=$nonce;"X-AIMall-Signature"=(Hmac-Hex $secret $canonical)}
}
function Add-Check([Collections.Generic.List[object]]$checks,[string]$id,[bool]$passed,[string]$detail){$checks.Add([ordered]@{id=$id;passed=$passed;detail=$detail})}
function Counter([object]$summary,[string]$name){$property=$summary.counters.PSObject.Properties[$name]; if($property){return [int]$property.Value}; return 0}
function Same-Array([object[]]$actual,[object[]]$expected){return (($actual -join "|") -eq ($expected -join "|"))}

Import-DotEnv (Join-Path $root ".env")
$chat=Get-Content -Raw -Encoding UTF8 (Join-Path $root $ChatEvidence) | ConvertFrom-Json
$integration=Invoke-SignedGet "/health/integration"
$summary=Invoke-SignedGet "/observability/summary"
$primary=if($env:AGNES_PRIMARY_MODEL){$env:AGNES_PRIMARY_MODEL}else{$env:AGNES_MODEL}
$fast=if($env:AGNES_FAST_MODEL){$env:AGNES_FAST_MODEL}else{$env:AGNES_MODEL}
$fallback=$env:AGNES_FALLBACK_MODEL
$generationExpected=@($primary); if($fallback -and $fallback -ne $primary){$generationExpected+= $fallback}
$planningExpected=@($primary); if($fallback -and $fallback -ne $primary){$planningExpected+= $fallback}
$modelKey="llm.model.$primary.calls"
$checks=[Collections.Generic.List[object]]::new()
Add-Check $checks "S19-LIVE-01" ($integration.status -in @("UP","DEGRADED") -and $integration.metricsSchemaVersion -eq "AIMALL_AGENT_METRICS_V1") "signed integration health exposes routing metadata"
Add-Check $checks "S19-LIVE-02" (@($chat.results).Count -eq 1 -and $chat.results[0].status -eq "SUCCESS") "one real routed Agent request completed"
Add-Check $checks "S19-LIVE-03" ([bool]$integration.modelRouting.enabled) "model routing is enabled in the running service"
Add-Check $checks "S19-LIVE-04" (Same-Array @($integration.modelRouting.generationModels) $generationExpected) "generation chain matches primary and optional fallback configuration"
Add-Check $checks "S19-LIVE-05" (Same-Array @($integration.modelRouting.planningModels) $planningExpected) "complex planning chain matches primary and optional fallback configuration"
Add-Check $checks "S19-LIVE-06" ((Counter $summary "llm.purpose.PLANNING.calls") -ge 1 -and (Counter $summary "llm.purpose.GENERATION.calls") -ge 1) "planning and generation purpose counters advanced"
Add-Check $checks "S19-LIVE-07" ((Counter $summary $modelKey) -ge 2 -and (Counter $summary "llm.fallback.calls") -eq 0) "real request used the configured primary without unnecessary fallback"
$byModel=$summary.cost.byModel.PSObject.Properties[$primary]
Add-Check $checks "S19-LIVE-08" ($byModel -and $summary.cost.byPurpose.PLANNING -and $summary.cost.byPurpose.GENERATION -and $summary.cost.inputTokens -gt 0) "token and cost dimensions attribute both routed purposes"
$result=[ordered]@{
    stage=19;generatedAt=[DateTimeOffset]::Now.ToString("o");runId=$chat.runId;datasetVersion=$chat.datasetVersion
    passed=@($checks|Where-Object{-not $_.passed}).Count -eq 0;checks=$checks
    routing=[ordered]@{fastModel=$fast;primaryModel=$primary;fallbackModel=$fallback;generationModels=@($integration.modelRouting.generationModels);planningModels=@($integration.modelRouting.planningModels)}
    metrics=[ordered]@{planningCalls=(Counter $summary "llm.purpose.PLANNING.calls");generationCalls=(Counter $summary "llm.purpose.GENERATION.calls");primaryCalls=(Counter $summary $modelKey);fallbackCalls=(Counter $summary "llm.fallback.calls");inputTokens=$summary.cost.inputTokens;outputTokens=$summary.cost.outputTokens;pricingStatus=$summary.cost.pricingStatus;llmP95Ms=$summary.latencyMs.llm.p95}
}
$outputPath=Join-Path $root $Output; New-Item -ItemType Directory -Force -Path (Split-Path $outputPath)|Out-Null
$result|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $outputPath -Encoding UTF8
$result|ConvertTo-Json -Depth 20
if(-not $result.passed){throw "Stage 19 live smoke failed: $outputPath"}

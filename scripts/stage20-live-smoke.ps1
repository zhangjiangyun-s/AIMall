param(
    [string]$Output=".acceptance/stage20/live-evidence.json",
    [string]$ChatEvidence=".acceptance/stage20/live-chat-final.json",
    [string]$AiBaseUrl="http://127.0.0.1:8000"
)
$ErrorActionPreference="Stop";$root=(Resolve-Path (Join-Path $PSScriptRoot "..")).Path
function Import-DotEnv([string]$path){foreach($line in Get-Content -LiteralPath $path -Encoding UTF8){$text=$line.Trim();if(-not $text -or $text.StartsWith("#") -or -not $text.Contains("=")){continue};$parts=$text.Split("=",2);[Environment]::SetEnvironmentVariable($parts[0].Trim(),$parts[1].Trim().Trim('"').Trim("'"),"Process")}}
function Sha256-Hex([byte[]]$bytes){$sha=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($sha.ComputeHash($bytes))-replace '-','').ToLowerInvariant()}finally{$sha.Dispose()}}
function Hmac-Hex([string]$secret,[string]$content){$hmac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret));try{return ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($content)))-replace '-','').ToLowerInvariant()}finally{$hmac.Dispose()}}
function Invoke-SignedGet([string]$path){$keyId=if($env:AIMALL_JAVA_TO_AI_KEY_ID){$env:AIMALL_JAVA_TO_AI_KEY_ID}else{"legacy"};$secret=if($env:AIMALL_JAVA_TO_AI_SECRET){$env:AIMALL_JAVA_TO_AI_SECRET}else{$env:AIMALL_INTERNAL_API_SECRET};if([string]::IsNullOrWhiteSpace($secret)){throw "Java-to-AI HMAC secret is not configured."};$timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString();$nonce=[Guid]::NewGuid().ToString("N");$empty=Sha256-Hex ([byte[]]::new(0));$canonical="GET`n$path`n`n$empty`n$empty`n$keyId`n$timestamp`n$nonce";Invoke-RestMethod -Uri "$AiBaseUrl$path" -TimeoutSec 20 -Headers @{"X-AIMall-Key-Id"=$keyId;"X-AIMall-Timestamp"=$timestamp;"X-AIMall-Nonce"=$nonce;"X-AIMall-Signature"=(Hmac-Hex $secret $canonical)}}
function Add-Check([Collections.Generic.List[object]]$checks,[string]$id,[bool]$passed,[string]$detail){$checks.Add([ordered]@{id=$id;passed=$passed;detail=$detail})}
function Counter([object]$summary,[string]$name){$property=$summary.counters.PSObject.Properties[$name];if($property){return [int]$property.Value};return 0}

Import-DotEnv (Join-Path $root ".env")
$chat=Get-Content -Raw -Encoding UTF8 (Join-Path $root $ChatEvidence)|ConvertFrom-Json;$results=@($chat.results);$summary=Invoke-SignedGet "/observability/summary";$integration=Invoke-SignedGet "/health/integration"
$order=$results|Where-Object caseId -eq "AIM-EVAL-HYBRID-001";$product=$results|Where-Object caseId -eq "AIM-EVAL-HYBRID-002"
function Agents([object]$result){@($result.done.agentSteps|ForEach-Object{$_.agent}|Where-Object{$_}|Select-Object -Unique)}
function Tools([object]$result){@($result.done.toolCalls|ForEach-Object{$_.name})}
$publicPayload=@($results|ForEach-Object{[ordered]@{agentSteps=$_.done.agentSteps;timelineEvents=$_.done.timelineEvents}})|ConvertTo-Json -Depth 20 -Compress
$traceFiles=Get-ChildItem (Join-Path $root "logs/agent-traces") -Filter *.jsonl|Sort-Object LastWriteTimeUtc -Descending
$traceMatches=@();foreach($file in $traceFiles|Select-Object -First 2){foreach($line in Get-Content $file.FullName -Encoding UTF8){if($line.Contains($chat.runId)){$traceMatches+=($line|ConvertFrom-Json)}}}
$completeDelegations=@($traceMatches|ForEach-Object{@($_.supervisorPlan.delegations)}|Where-Object{$_.status -eq "COMPLETED"})
$checks=[Collections.Generic.List[object]]::new()
Add-Check $checks "S20-LIVE-01" ($integration.status -in @("UP","DEGRADED") -and [bool]$integration.modelRouting.enabled) "signed runtime dependencies and model routing are available"
Add-Check $checks "S20-LIVE-02" ($results.Count -eq 2 -and @($results|Where-Object status -eq "SUCCESS").Count -eq 2) "two real hybrid requests completed"
Add-Check $checks "S20-LIVE-03" (((Agents $order)-join '|') -eq "ORDER_SPECIALIST|POLICY_SPECIALIST") "order specialist precedes policy specialist"
Add-Check $checks "S20-LIVE-04" (((Agents $product)-join '|') -eq "PRODUCT_SPECIALIST|POLICY_SPECIALIST") "product specialist precedes policy specialist"
Add-Check $checks "S20-LIVE-05" (((Tools $order)-join '|') -eq "get_my_order_detail|search_policy_kb" -and ((Tools $product)-join '|') -eq "get_product_detail|search_policy_kb") "specialists execute only expected domain tools"
$privateMarkers=@('"thought"','"candidateTools"','"arguments"')
Add-Check $checks "S20-LIVE-06" (@($privateMarkers|Where-Object{$publicPayload.Contains($_)}).Count -eq 0) "public Agent steps and timeline omit internal reasoning, capabilities and arguments"
Add-Check $checks "S20-LIVE-07" ((Counter $summary "multi_agent.requests") -ge 2 -and (Counter $summary "multi_agent.delegations") -ge 4 -and (Counter $summary "multi_agent.failures") -eq 0 -and (Counter $summary "multi_agent.legacy_fallbacks") -eq 0) "multi-agent metrics record successful bounded delegation"
Add-Check $checks "S20-LIVE-08" ($traceMatches.Count -eq 2 -and $completeDelegations.Count -eq 4) "internal traces retain four completed structured delegations"
$result=[ordered]@{stage=20;generatedAt=[DateTimeOffset]::Now.ToString("o");runId=$chat.runId;datasetVersion=$chat.datasetVersion;passed=@($checks|Where-Object{-not $_.passed}).Count -eq 0;checks=$checks;metrics=[ordered]@{requests=(Counter $summary "multi_agent.requests");delegations=(Counter $summary "multi_agent.delegations");failures=(Counter $summary "multi_agent.failures");skipped=(Counter $summary "multi_agent.skipped");duplicateCallsPrevented=(Counter $summary "multi_agent.duplicate_calls_prevented");legacyFallbacks=(Counter $summary "multi_agent.legacy_fallbacks");llmP95Ms=$summary.latencyMs.llm.p95}}
$outputPath=Join-Path $root $Output;New-Item -ItemType Directory -Force -Path (Split-Path $outputPath)|Out-Null;$result|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $outputPath -Encoding UTF8;$result|ConvertTo-Json -Depth 20;if(-not $result.passed){throw "Stage 20 live smoke failed: $outputPath"}

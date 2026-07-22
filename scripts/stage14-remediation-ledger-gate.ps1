param(
    [switch]$RequireNoBlocked,
    [string]$Ledger = ".acceptance/stage14/remediation-ledger.json",
    [string]$Output = ".acceptance/stage14/ledger-gate.json"
)

$ErrorActionPreference="Stop";$root=(Resolve-Path(Join-Path $PSScriptRoot "..")).Path;$ledgerPath=Join-Path $root $Ledger
if(-not(Test-Path $ledgerPath)){throw "Ledger missing: $ledgerPath"};$ledgerData=Get-Content -Raw -Encoding UTF8 $ledgerPath|ConvertFrom-Json
$allowedStatuses=@("UNVERIFIED","PLANNED","IN_PROGRESS","READY_FOR_TEST","ACCEPTED","BLOCKED","ROLLED_BACK")
$allowedOwners=@("Backend","AI","DBA","SRE","QA","Security")
$results=[Collections.Generic.List[object]]::new();$ids=@{}
foreach($record in @($ledgerData.records)){
    $fail=[Collections.Generic.List[string]]::new();$id=[string]$record.id
    if($id-notmatch '^[A-Z][A-Z0-9-]+-[0-9]{3}$'){$fail.Add("invalidId")};if($ids.ContainsKey($id)){$fail.Add("duplicateId")}else{$ids[$id]=$true}
    if($record.priority-notin @("P0","P1","P2","P3")){$fail.Add("invalidPriority")};if($record.status-notin $allowedStatuses){$fail.Add("invalidStatus")}
    foreach($field in @("currentState","targetState","rollback","deadline","escalationOwner","currentRisk")){if([string]::IsNullOrWhiteSpace([string]$record.$field)){$fail.Add("missing:$field")}}
    foreach($field in @("codeScope","dataChanges","eventsTasks","owners","prerequisites")){if(@($record.$field).Count-eq 0){$fail.Add("empty:$field")}}
    if(@($record.owners|Where-Object{$_-notin $allowedOwners}).Count-gt 0){$fail.Add("invalidOwner")};if($record.escalationOwner-notin $allowedOwners){$fail.Add("invalidEscalationOwner")}
    $date=[DateTime]::MinValue;if(-not[DateTime]::TryParseExact([string]$record.deadline,"yyyy-MM-dd",[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$date)){$fail.Add("invalidDeadline")}
    if($record.status-eq "ACCEPTED"){
        foreach($field in @("code","database","tests","monitoring","rollback")){if(@($record.completionEvidence.$field).Count-eq 0){$fail.Add("acceptedEvidence:$field")}}
    }
    if($record.status-eq "BLOCKED"){
        if(@($record.blockers).Count-eq 0){$fail.Add("missingBlockers")};if([string]::IsNullOrWhiteSpace([string]$record.temporaryMeasure)){$fail.Add("missingTemporaryMeasure")}
    }
    $results.Add([ordered]@{id=$id;status=$record.status;valid=$fail.Count-eq 0;failures=$fail})
}
$invalid=@($results|Where-Object{-not $_.valid});$blocked=@($ledgerData.records|Where-Object{$_.status-eq "BLOCKED"});$accepted=@($ledgerData.records|Where-Object{$_.status-eq "ACCEPTED"})
$result=[ordered]@{stage=14;source="AIMALL_ENGINEERING_PRODUCTION_REMEDIATION_PLAN.md section 14";generatedAt=[DateTimeOffset]::Now.ToString("o");ledgerVersion=$ledgerData.ledgerVersion;total=@($ledgerData.records).Count;validCount=@($ledgerData.records).Count-$invalid.Count;structurallyPassed=$invalid.Count-eq 0;acceptedCount=$accepted.Count;blockedCount=$blocked.Count;releaseReady=$blocked.Count-eq 0;records=$results;blockedIds=@($blocked|ForEach-Object{$_.id})}
$out=Join-Path $root $Output;New-Item -ItemType Directory -Force -Path(Split-Path $out)|Out-Null;$result|ConvertTo-Json -Depth 12|Set-Content -Encoding UTF8 $out;$result|ConvertTo-Json -Depth 12
if(-not$result.structurallyPassed){throw "Stage 14 ledger invalid: $($invalid.id -join ',')"};if($RequireNoBlocked-and-not$result.releaseReady){throw "Stage 14 ledger contains blocked work: $($result.blockedIds -join ',')"}

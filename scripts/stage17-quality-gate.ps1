param([string]$Root=(Split-Path -Parent $PSScriptRoot),[string]$Output='.acceptance/stage17/quality-gate.json',[switch]$RequireEngineering,[switch]$RequireProduction)
$ErrorActionPreference='Stop';$rootPath=[IO.Path]::GetFullPath($Root)
function Read-Utf8([string]$p){[IO.File]::ReadAllText((Join-Path $rootPath $p),[Text.UTF8Encoding]::new($false,$true))}
function Read-Json([string]$p){Get-Content (Join-Path $rootPath $p) -Raw -Encoding UTF8|ConvertFrom-Json}
$state=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/payment/PaymentOrderState.java'
$reconcile=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/PaymentReconciliationService.java'
$workflow=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/PaymentReconciliationWorkflowService.java'
$controller=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/admin/PaymentReconciliationController.java'
$refund=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/mapper/OmsRefundRecordMapper.java'
$refundProcessor=Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/RefundTaskProcessor.java'
$migration=Read-Utf8 'aimall-server/src/main/resources/db/migration/V20260721_1700__payment_reconciliation_workflow.sql'
$adminView=Read-Utf8 'aimall-admin/src/views/PaymentReconciliationView.vue';$verification=Read-Json '.acceptance/stage17/verification.json'
$states=@('INIT','CREATED','WAITING_PAYMENT','PROCESSING','PAID','FAILED','UNKNOWN','QUERYING','CLOSING','CLOSED','CLOSE_UNKNOWN','PARTIALLY_REFUNDED','REFUNDED','RISK_HOLD','CHARGEBACK','CANCELLED_BY_CHANNEL','MANUAL_REVIEW_REQUIRED')
$types=@('LOCAL_MISSING_CHANNEL','CHANNEL_MISSING_LOCAL','AMOUNT_MISMATCH','DUPLICATE_CALLBACK','REFUND_MISMATCH','LATE_PAYMENT_AFTER_CLOSE')
$checks=@(
 [ordered]@{id='S17-01';name='Payment state and dispute domain contract';engineering=(($states|Where-Object{-not $state.Contains($_)}).Count -eq 0);production=$false;evidence='Explicit payment state matrix'},
 [ordered]@{id='S17-02';name='Six-class reconciliation with query and financial hold';engineering=(($types|Where-Object{-not $reconcile.Contains($_)}).Count -eq 0 -and $reconcile.Contains('paymentGateway.query') -and $reconcile.Contains('placeFinancialHold'));production=$false;evidence='Channel query, classification and freeze'},
 [ordered]@{id='S17-03';name='Correction event and four-eyes workflow';engineering=($workflow.Contains('submitCorrection') -and $workflow.Contains('reviewerId') -and $controller.Contains('/corrections/{eventId}/review') -and -not $controller.Contains('/resolve') -and $adminView.Contains('reviewCorrection'));production=$false;evidence='CAS workflow and admin console'},
 [ordered]@{id='S17-04';name='Unknown refund query-first contract';engineering=($migration.Contains('refund_state') -and $migration.Contains('channel_reference') -and $migration.Contains('last_query_at') -and $migration.Contains('query_count') -and $migration.Contains('reconciliation_status') -and $migration.Contains('manual_owner') -and $refundProcessor.Contains('queryUnknownRefund') -and $refund.Contains("refund_state = 'UNKNOWN'"));production=$false;evidence='Explicit fields and provider query before retry'},
 [ordered]@{id='S17-05';name='Migration and regression evidence';engineering=([bool]$verification.java.passed -and [bool]$verification.realMySql.passed -and [bool]$verification.adminBuild.passed);production=$false;evidence='Java 176, MySQL 24, Admin build'}
)
$ep=@($checks|Where-Object engineering).Count;$pp=@($checks|Where-Object production).Count
$result=[ordered]@{stage=17;source='AIMALL_ENGINEERING_PRODUCTION_REMEDIATION_PLAN.md section 17';generatedAt=[DateTimeOffset]::Now.ToString('o');total=$checks.Count;engineeringPassedCount=$ep;engineeringFullyCompleted=$ep -eq $checks.Count;productionPassedCount=$pp;productionReady=$pp -eq $checks.Count;checks=$checks}
$path=[IO.Path]::GetFullPath((Join-Path $rootPath $Output));[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))|Out-Null;[IO.File]::WriteAllText($path,($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
if($RequireEngineering -and -not $result.engineeringFullyCompleted){throw 'Stage 17 engineering gate failed'};if($RequireProduction -and -not $result.productionReady){throw 'Stage 17 production gate failed'};$result|ConvertTo-Json -Depth 8

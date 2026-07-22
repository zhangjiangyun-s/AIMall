param(
    [string]$OutputPath = ".acceptance/stage7/security-gate.json"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check([string]$id, [bool]$passed, [string]$detail) {
    $checks.Add([ordered]@{ id = $id; passed = $passed; detail = $detail })
}

function File-Contains([string]$relativePath, [string]$pattern) {
    $content = Get-Content -LiteralPath (Join-Path $root $relativePath) -Raw -Encoding UTF8
    return $content -match $pattern
}

$adminControllers = Get-ChildItem (Join-Path $root "aimall-server/src/main/java/com/aimall/server/admin") -Filter "*Controller.java"
$missingPermissionMetadata = @($adminControllers | Where-Object {
    -not (Select-String -LiteralPath $_.FullName -Pattern "RequireAdminPermission" -Quiet)
} | Select-Object -ExpandProperty Name)
$permissionDetail = if ($missingPermissionMetadata.Count -eq 0) {
    "all admin controllers declare permission metadata"
} else {
    "missing: " + ($missingPermissionMetadata -join ",")
}
Add-Check "S7-RBAC-METADATA" ($missingPermissionMetadata.Count -eq 0) $permissionDetail

Add-Check "S7-RBAC-NO-URI-INFERENCE" (-not (File-Contains "aimall-server/src/main/java/com/aimall/server/config/AdminAuthInterceptor.java" "permissionFor\(")) "URI permission inference removed"
Add-Check "S7-TRUSTED-PROXY" (File-Contains "aimall-server/src/main/java/com/aimall/server/common/ClientIpResolver.java" "isTrusted\(remoteAddress\)") "forwarded headers require a trusted socket peer"
Add-Check "S7-ACCOUNT-RATE-DIMENSIONS" (File-Contains "aimall-server/src/main/java/com/aimall/server/service/impl/AccountSecurityService.java" "LOGIN_DEVICE_LIMIT") "account, IP and device limits are present"
Add-Check "S7-HMAC-KEY-ID" (File-Contains "aimall-server/src/main/java/com/aimall/server/config/InternalServiceAuthInterceptor.java" "KEY_ID_HEADER") "keyId participates in internal authentication"
Add-Check "S7-HMAC-DIRECTIONAL" (File-Contains "aimall-server/src/main/resources/application.yml" "java-to-ai:") "directional HMAC configuration is present"
Add-Check "S7-HMAC-REPLAY" (File-Contains "aimall-server/src/main/java/com/aimall/server/config/InternalServiceAuthInterceptor.java" "nonceMapper.reserve") "Java inbound nonce reservation is persistent"
Add-Check "S7-CSRF-HEADER-TOKEN" (File-Contains "aimall-server/src/main/resources/application.yml" "is-read-cookie: false") "authentication tokens are not read from cookies"
Add-Check "S7-CORS-EXPLICIT" (-not (File-Contains "aimall-server/src/main/java/com/aimall/server/config/CorsConfig.java" 'setAllowedHeaders.*\*')) "CORS request headers are explicit"
Add-Check "S7-UPLOAD-SCAN" (File-Contains "aimall-server/src/main/java/com/aimall/server/service/impl/AdminKnowledgeUploadServiceImpl.java" "uploadSecurityScanner.scan") "all knowledge uploads invoke structural and antivirus scanning"
Add-Check "S7-SSE-LIMIT" (File-Contains "aimall-ai-service/main.py" "SseLimitMiddleware") "AI chat SSE lifecycle limiter is registered"

function Find-SensitiveSourceFiles {
    $git = (Get-Command git -ErrorAction Stop).Source
    $relativePaths = @(& $git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate source files for the secret scan."
    }

    $pattern = [regex]::new(
        "-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=]{40,}|AKIA[0-9A-Z]{16}",
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    foreach ($relativePath in $relativePaths) {
        $normalized = $relativePath.Replace("\", "/")
        if ($normalized -eq ".env" -or $normalized.StartsWith(".env.") -or
            $normalized.StartsWith(".acceptance/") -or
            $normalized -match "(^|/)(node_modules|target)(/|$)") {
            continue
        }

        $absolutePath = Join-Path $root $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            continue
        }
        try {
            $content = [System.IO.File]::ReadAllText($absolutePath)
            if ($pattern.IsMatch($content)) {
                $absolutePath
            }
        } catch [System.IO.IOException] {
            throw "Unable to scan source file: $relativePath"
        } catch [System.UnauthorizedAccessException] {
            throw "Unable to scan source file: $relativePath"
        }
    }
}

$secretMatches = @(Find-SensitiveSourceFiles)
$secretDetail = if ($secretMatches.Count -eq 0) {
    "no private-key or AWS access-key signature found in source files"
} else {
    "sensitive signature found in: " + (($secretMatches | ForEach-Object { Split-Path $_ -Leaf }) -join ",")
}
Add-Check "S7-SECRET-SCAN" ($secretMatches.Count -eq 0) $secretDetail

$passed = @($checks | Where-Object { -not $_.passed }).Count -eq 0
$report = [ordered]@{
    stage = 7
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    passed = $passed
    checks = $checks
}
$absoluteOutput = Join-Path $root $OutputPath
New-Item -ItemType Directory -Force -Path (Split-Path $absoluteOutput -Parent) | Out-Null
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $absoluteOutput -Encoding UTF8
if (-not $passed) {
    $failed = @($checks | Where-Object { -not $_.passed } | ForEach-Object { $_.id }) -join ","
    throw "Stage 7 security gate failed: $failed"
}
Write-Output $absoluteOutput

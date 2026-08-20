# Bypass Ads 0.1.0 self-use build.
# Requires a third-party GKD subscription (JSON/JSON5) with splash rules.
# The complete build FAILS (never silently falls back to the small fixture)
# when the full rule stack cannot be produced.
#
# Usage:
#   .\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"
#   .\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\primary.json5" -AdditionalSubscriptionPath "D:\rules\secondary.json5"
#   .\tools\build_selfuse.ps1 -SubscriptionPaths "D:\rules\primary.json5","D:\rules\secondary.json5"
param(
    [string]$SubscriptionPath,
    [string[]]$AdditionalSubscriptionPath = @(),
    [string[]]$SubscriptionPaths = @()
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$generator = Join-Path $PSScriptRoot "build_splash_bundle.py"
$localBundle = Join-Path $repoRoot "app\src\main\assets\bypass_splash_rules.local.json"
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\gkd\debug\app-gkd-debug.apk"

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

Write-Step "Bypass Ads 0.1.0 self-use build"

# 1. validate inputs. The first source is primary; later sources add only
# non-conflicting coverage. The Python generator reports conflicts instead of
# concatenating incompatible same-name groups.
$ruleSources = @()
if ($SubscriptionPaths.Count -gt 0) {
    $ruleSources += $SubscriptionPaths
} elseif ($SubscriptionPath) {
    $ruleSources += $SubscriptionPath
    $ruleSources += $AdditionalSubscriptionPath
}
if ($ruleSources.Count -eq 0) {
    Write-Error "Provide -SubscriptionPath or -SubscriptionPaths."
    exit 1
}
foreach ($ruleSource in $ruleSources) {
    if (-not (Test-Path $ruleSource)) {
        Write-Error "Input subscription not found: $ruleSource"
        exit 1
    }
}
Write-Step "Primary subscription: $($ruleSources[0])"
if ($ruleSources.Count -gt 1) { Write-Step "Secondary subscriptions: $($ruleSources[1..($ruleSources.Count - 1)] -join ', ')" }

# 2. generate full bundle (filter + overrides + host rules + generic fallback + validate)
Write-Step "Generating full splash bundle (validator and coverage diff run inside generator)"
$generatorArgs = @($generator, $ruleSources[0])
if ($ruleSources.Count -gt 1) {
    foreach ($secondary in $ruleSources[1..($ruleSources.Count - 1)]) {
        $generatorArgs += "--additional"
        $generatorArgs += $secondary
    }
}
python @generatorArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Bundle generation FAILED - complete build aborted (no fixture fallback)."
    exit 1
}
if (-not (Test-Path $localBundle)) {
    Write-Error "Bundle output missing: $localBundle"
    exit 1
}
$bundleSize = (Get-Item $localBundle).Length
if ($bundleSize -lt 100000) {
    Write-Error "Bundle suspiciously small ($bundleSize bytes) - refusing to proceed (fixture fallback guard)."
    exit 1
}
Write-Step "Bundle: $localBundle ($bundleSize bytes)"

# 3. gradle build
Write-Step "Building APK (assembleGkdDebug)"
Push-Location $repoRoot
try {
    $gradle = "C:\Users\cruelworld\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat"
    & $gradle --console=plain --no-daemon :app:kspGkdDebugKotlin :app:assembleGkdDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle build FAILED"
        exit 1
    }
} finally {
    Pop-Location
}

# 4. APK self-check
if (-not (Test-Path $apkPath)) {
    Write-Error "APK not found: $apkPath"
    exit 1
}
$apkSize = (Get-Item $apkPath).Length
Write-Step "APK: $apkPath ($apkSize bytes)"

$aapt = "C:\Users\cruelworld\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt.exe"
$perms = & $aapt dump permissions $apkPath 2>&1
$hasInternet = ($perms | Select-String "android.permission.INTERNET") -ne $null
if ($hasInternet) {
    Write-Error "APK contains INTERNET permission - refusing self-use build."
    exit 1
}

# verify the APK actually packs the full local bundle (not the fixture)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
$entry = $zip.Entries | Where-Object { $_.FullName -eq "assets/bypass_splash_rules.local.json" }
if (-not $entry) {
    $zip.Dispose()
    Write-Error "APK does not contain bypass_splash_rules.local.json"
    exit 1
}
$ms = New-Object System.IO.MemoryStream
$es = $entry.Open()
$es.CopyTo($ms)
$es.Close()
$bytes = $ms.ToArray()
$ms.Close()
$zip.Dispose()
$sha = [System.Security.Cryptography.SHA256]::Create()
$apkBundleSha = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
$localSha = ([BitConverter]::ToString($sha.ComputeHash([System.IO.File]::ReadAllBytes($localBundle)))).Replace("-", "").ToLowerInvariant()
if ($apkBundleSha -ne $localSha) {
    Write-Error "APK bundle SHA256 mismatch: apk=$apkBundleSha local=$localSha"
    exit 1
}

# 5. report
Write-Host ""
Write-Host "================================" -ForegroundColor Green
Write-Host " Bypass Ads 0.1.0 self-use build PASS" -ForegroundColor Green
Write-Host "================================" -ForegroundColor Green
Write-Host "package      : app.bypassads.debug"
Write-Host "APK path     : $apkPath"
Write-Host "APK size     : $apkSize bytes"
Write-Host "bundle SHA256: $localSha"
Write-Host "INTERNET     : absent"
Write-Host ""
exit 0

param(
    [Parameter(Mandatory = $true)][string]$RunId
)

$ReportDir = Join-Path $PSScriptRoot "reports"
$jsonPath = Join-Path $ReportDir "$RunId-results.json"
if (-not (Test-Path $jsonPath)) {
    Write-Error "Missing $jsonPath"
    exit 1
}

$results = Get-Content $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
$failed = @($results | Where-Object { -not $_.pass })
Write-Host "VERIFY run=$RunId total=$($results.Count) failed=$($failed.Count)"

if ($failed.Count -eq 0) {
    Write-Host "ALL_PASS"
    exit 0
}

Write-Host "Re-running failed cases..."
$ids = ($failed | ForEach-Object { $_.id }) -join ","
& (Join-Path $PSScriptRoot "run-prod-eval.ps1") -RunId "$RunId-retry" -Category all

# Filter retry to only failed ids by re-reading cases.json - simpler: manual list
$cases = Get-Content (Join-Path $PSScriptRoot "cases.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$retryCases = @($cases | Where-Object { $failed.id -contains $_.id })
if ($retryCases.Count -eq 0) { exit 1 }

# Inline retry with same runner logic would duplicate; emit list for operator
Write-Host "Retry IDs: $($failed.id -join ', ')"
exit 1

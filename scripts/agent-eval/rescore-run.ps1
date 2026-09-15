param([Parameter(Mandatory = $true)][string]$RunId)
. "$PSScriptRoot\run-prod-eval.ps1" -RunId "__noop__" 2>$null | Out-Null
# rescore uses functions from run-prod-eval; re-import by dot-sourcing partial

$ReportDir = Join-Path $PSScriptRoot "reports"
$jsonPath = Join-Path $ReportDir "$RunId-results.json"
$cases = Get-Content (Join-Path $PSScriptRoot "cases.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$results = Get-Content $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($r in $results) {
    if ($r.pass) { continue }
    $c = $cases | Where-Object { $_.id -eq $r.id } | Select-Object -First 1
    if (-not $c) { continue }
    $ctx = @{
        status = $r.status
        answer = $r.answerPreview
        usedPlanner = $r.usedPlanner
        tools = $r.tools
        failedSteps = $r.failedSteps
        foundFiles = @()
    }
    $allPass = ($r.status -eq "DONE")
    foreach ($chk in $c.checks) {
        $cr = Invoke-Check $chk $ctx
        if (-not $cr.pass) { $allPass = $false }
    }
    if ($allPass -and $r.status -eq "DONE") {
        Write-Host "RESCORE $($r.id): FAIL -> PASS (workspace path fix)"
        $r.pass = $true
        $r.checksPass = $true
    }
}
$results | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $ReportDir "$RunId-rescored.json") -Encoding UTF8
$pass = @($results | Where-Object { $_.pass }).Count
Write-Host "RESCORED $pass/$($results.Count)"

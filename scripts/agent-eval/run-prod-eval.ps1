param(
    [string]$Tier = "all",
    [string]$Category = "all",
    [string]$RunId = "",
    [string]$Ids = "",
    [string]$CasesFile = "",
    [switch]$SmokeOnly
)

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
$RepoRoot = Split-Path -Parent $Root
$EvalDir = $PSScriptRoot
$PromptDir = Join-Path $EvalDir "prompts"
$ReportDir = Join-Path $EvalDir "reports"
$WorkspaceRoots = @(
    (Join-Path $env:USERPROFILE ".miniagent\workspace")
)
$repoWs = Join-Path $RepoRoot "workspace"
if (Test-Path $repoWs) {
    $WorkspaceRoots += $repoWs
}

if ($RunId -eq "") {
    $RunId = Get-Date -Format "yyyyMMdd-HHmmss"
}
if ($SmokeOnly) {
    $Tier = "smoke"
}

New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
. (Join-Path $RepoRoot "agent-api.ps1")

$ErrorPhrases = @("ABORTED", "SSE_ERROR", "HTTP_ERR", "NO_END", "Exception", "stacktrace")
$phraseFile = Join-Path $EvalDir "error-phrases.txt"
if (Test-Path $phraseFile) {
    $ErrorPhrases += [IO.File]::ReadAllLines($phraseFile, [Text.Encoding]::UTF8) |
        Where-Object { $_ -and $_.Trim() -ne "" }
}

function Find-WorkspaceFile([string]$Glob) {
    $hits = @()
    foreach ($root in $WorkspaceRoots) {
        if (-not (Test-Path $root)) { continue }
        $hits += @(Get-ChildItem -Path $root -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like $Glob -or $_.FullName.Replace("\", "/") -like "*$Glob*" })
    }
    return @($hits | Sort-Object LastWriteTime -Descending | Select-Object -First 3)
}

function Read-SseAnswer([string]$LogFile) {
    if (-not (Test-Path $LogFile)) { return @{ answer = ""; eventEnd = $false; eventErr = $false; err = "" } }
    $sse = [IO.File]::ReadAllText($LogFile, [Text.Encoding]::UTF8)
    $lastEvent = ""
    $endAnswer = ""
    $tokenBuf = New-Object Text.StringBuilder
    $eventEnd = $false
    $eventErr = $false
    $err = ""
    foreach ($line in ($sse -split "`n")) {
        $line = $line.TrimEnd("`r")
        if ($line.StartsWith("event:")) {
            $lastEvent = $line.Substring(6).Trim()
        } elseif ($line.StartsWith("data:")) {
            $data = $line.Substring(5).TrimStart()
            if ($lastEvent -eq "end") {
                $eventEnd = $true
                $endAnswer = $data
            } elseif ($lastEvent -eq "error") {
                $eventErr = $true
                $err = $data
            } elseif ($lastEvent -eq "token") {
                [void]$tokenBuf.Append($data)
            }
        }
    }
    $answer = if ($eventEnd -and $endAnswer -ne "") { $endAnswer } else { $tokenBuf.ToString() }
    return @{ answer = $answer; eventEnd = $eventEnd; eventErr = $eventErr; err = $err }
}

function Invoke-Check($check, $ctx) {
    $type = $check.type
    $pass = $false
    $reason = ""
    switch ($type) {
        "status_done" {
            $pass = ($ctx.status -eq "DONE")
            if (-not $pass) { $reason = "status=$($ctx.status)" }
        }
        "no_planner" {
            $pass = (-not $ctx.usedPlanner)
            if (-not $pass) { $reason = "usedPlanner=true" }
        }
        "no_tool_errors" {
            $pass = (@($ctx.failedSteps).Count -eq 0)
            if (-not $pass) { $reason = ($ctx.failedSteps -join ";") }
        }
        "no_error_phrases" {
            $pass = $true
            foreach ($p in $ErrorPhrases) {
                if ($ctx.answer -like "*$p*") { $pass = $false; $reason = "hit:$p"; break }
            }
        }
        "response_contains" {
            $pass = $ctx.answer.Contains($check.value)
            if (-not $pass) { $reason = "missing in answer" }
        }
        "response_not_contains" {
            $pass = -not $ctx.answer.Contains($check.value)
            if (-not $pass) { $reason = "forbidden text present" }
        }
        "response_regex" {
            $pass = ($ctx.answer -match $check.value)
            if (-not $pass) { $reason = "regex miss" }
        }
        "response_min_length" {
            $pass = ($ctx.answer.Length -ge [int]$check.number)
            if (-not $pass) { $reason = "len=$($ctx.answer.Length)" }
        }
        "tool_used" {
            $pass = @($ctx.tools) -contains $check.value
            if (-not $pass) { $reason = "tools=$($ctx.tools -join ',')" }
        }
        "workspace_file_exists" {
            $hits = Find-WorkspaceFile $check.glob
            $pass = ($hits.Count -gt 0)
            if (-not $pass) { $reason = "missing $($check.glob)" }
            else { $ctx.foundFiles += $hits[0].FullName }
        }
        "workspace_file_contains" {
            $hits = Find-WorkspaceFile $check.glob
            if ($hits.Count -eq 0) {
                $pass = $false
                $reason = "missing $($check.glob)"
            } else {
                try {
                    $blob = [IO.File]::ReadAllText($hits[0].FullName)
                    $pass = $blob.Contains($check.value)
                    if (-not $pass) { $reason = "content miss in $($hits[0].Name)" }
                } catch {
                    $pass = $false
                    $reason = $_.Exception.Message
                }
            }
        }
        "workspace_file_min_length" {
            $hits = Find-WorkspaceFile $check.glob
            if ($hits.Count -eq 0) {
                $pass = $false
                $reason = "missing $($check.glob)"
            } else {
                $pass = ($hits[0].Length -ge [int]$check.number)
                if (-not $pass) { $reason = "bytes=$($hits[0].Length)" }
            }
        }
        "workspace_file_min_lines" {
            $hits = Find-WorkspaceFile $check.glob
            if ($hits.Count -eq 0) {
                $pass = $false
                $reason = "missing $($check.glob)"
            } else {
                $lines = ([IO.File]::ReadAllLines($hits[0].FullName)).Count
                $pass = ($lines -ge [int]$check.number)
                if (-not $pass) { $reason = "lines=$lines" }
            }
        }
        default {
            $pass = $false
            $reason = "unknown check $type"
        }
    }
    return @{ type = $type; pass = $pass; reason = $reason }
}

$casesJsonPath = if ($CasesFile -ne "") { $CasesFile } else { Join-Path $EvalDir "cases.json" }
$cases = Get-Content $casesJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($Tier -ne "all") {
    $cases = @($cases | Where-Object { $_.tier -eq $Tier })
}
if ($Category -ne "all") {
    $cases = @($cases | Where-Object { $_.category -eq $Category })
}
if ($Ids -ne "") {
    $want = $Ids -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
    $cases = @($cases | Where-Object { $want -contains $_.id })
}

Write-Host "PROD_EVAL run=$RunId cases=$($cases.Count) tier=$Tier category=$Category"
$results = @()

foreach ($c in $cases) {
    # CSRF token rotates; refresh session before each case
    $api = Connect-Agent
    $promptPath = Join-Path $PromptDir ($c.promptFile -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path $promptPath)) {
        Write-Host "SKIP $($c.id) missing prompt"
        continue
    }
    $msg = [IO.File]::ReadAllText($promptPath, [Text.Encoding]::UTF8)
    $sessionId = "eval_${RunId}_$($c.id)"
    $logFile = Join-Path $ReportDir "$RunId-$($c.id).sse.log"
    $bodyObj = @{
        sessionId = $sessionId
        message = $msg
        permissionMode = "accept_edits"
        confirmPolicy = "auto"
    }
    $body = $bodyObj | ConvertTo-Json -Compress
    $enc = New-Object System.Text.UTF8Encoding $false
    $bodyBytes = $enc.GetBytes($body)

    Write-Host ""
    Write-Host "=== [$($c.category)/$($c.tier)] $($c.id) session=$sessionId ==="
    $t0 = Get-Date
    $httpStatus = "DONE"
    $httpErr = ""
    try {
        Invoke-WebRequest "$($api.Base)/chat/stream" -Method Post -Body $bodyBytes `
            -ContentType "application/json; charset=utf-8" -Headers $api.Headers -WebSession $api.Session `
            -TimeoutSec ([int]$c.timeoutSec) -OutFile $logFile -UseBasicParsing | Out-Null
    } catch {
        $httpStatus = "HTTP_ERR"
        $httpErr = $_.Exception.Message
    }

    # Wait until session task fully released (per-user concurrency gate)
    $waitSec = 0
    while ($waitSec -lt 30) {
        try {
            $st = Invoke-RestMethod "$($api.Base)/api/task-status?sessionId=$sessionId" `
                -Headers $api.Headers -WebSession $api.Session -TimeoutSec 10
            if (-not $st.data.running) { break }
        } catch { break }
        Start-Sleep -Seconds 2
        $waitSec += 2
    }
    $elapsedMs = [int](((Get-Date) - $t0).TotalMilliseconds)

    $parsed = Read-SseAnswer $logFile
    $status = if ($parsed.eventErr) { "SSE_ERROR" }
        elseif ($parsed.eventEnd) { "DONE" }
        elseif ($httpStatus -eq "HTTP_ERR") { "HTTP_ERR" }
        else { "NO_END" }

    $traces = $null
    try {
        $traces = (Invoke-RestMethod "$($api.Base)/api/traces?sessionId=$sessionId" `
            -Headers $api.Headers -WebSession $api.Session -TimeoutSec 30).data
    } catch { }

    $usedPlanner = $false
    $tools = New-Object System.Collections.Generic.List[string]
    $failedSteps = New-Object System.Collections.Generic.List[string]
    $signalsStep = ""
    if ($traces) {
        foreach ($s in $traces) {
            if ($s.stepType -eq "GOAL_COMPILED") { $usedPlanner = $true }
            if ($s.toolName) { [void]$tools.Add([string]$s.toolName) }
            if ($s.status -match "FAIL|ERROR") {
                [void]$failedSteps.Add("$($s.stepType)/$($s.toolName)/$($s.status)")
            }
            if ($s.stepType -eq "TASK_SIGNALS" -and $s.content) { $signalsStep = [string]$s.content }
        }
    }

    $ctx = @{
        status = $status
        answer = $parsed.answer
        usedPlanner = $usedPlanner
        tools = ($tools | Select-Object -Unique)
        failedSteps = $failedSteps
        foundFiles = @()
    }
    if ($httpErr -ne "") { $ctx.answer = "$httpErr $($ctx.answer)" }

    $checkResults = @()
    $allPass = $true
    foreach ($chk in $c.checks) {
        $r = Invoke-Check $chk $ctx
        $checkResults += $r
        if (-not $r.pass) { $allPass = $false }
    }
    $sloPass = ($elapsedMs -le [int]$c.sloMs)

    $row = [ordered]@{
        id = $c.id
        category = $c.category
        tier = $c.tier
        sessionId = $sessionId
        status = $status
        pass = ($allPass -and $status -eq "DONE")
        checksPass = $allPass
        sloPass = $sloPass
        elapsedMs = $elapsedMs
        sloMs = $c.sloMs
        usedPlanner = $usedPlanner
        tools = $ctx.tools
        failedSteps = @($failedSteps)
        answerPreview = if ($parsed.answer.Length -gt 200) { $parsed.answer.Substring(0, 200) } else { $parsed.answer }
        signalsSnippet = if ($signalsStep.Length -gt 120) { $signalsStep.Substring(0, 120) } else { $signalsStep }
        checkResults = $checkResults
        foundFiles = $ctx.foundFiles
    }
    $results += [pscustomobject]$row
    $mark = if ($row.pass) { "PASS" } else { "FAIL" }
    Write-Host "$mark $($c.id) ${elapsedMs}ms slo=$(if($sloPass){'OK'}else{'MISS'}) planner=$usedPlanner"
    Start-Sleep -Seconds 5
}

$jsonPath = Join-Path $ReportDir "$RunId-results.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8

function Get-Rate($items) {
    if ($items.Count -eq 0) { return "n/a" }
    $ok = @($items | Where-Object { $_.pass }).Count
    return "{0}/{1} ({2:N0}%)" -f $ok, $items.Count, (100.0 * $ok / $items.Count)
}

$md = @()
$md += "# Agent Production Eval Report"
$md += ""
$md += "- RunId: ``$RunId``"
$md += "- Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$md += "- Total: $(Get-Rate $results)"
$md += ""
foreach ($cat in @("qa", "simple", "complex")) {
    $sub = @($results | Where-Object { $_.category -eq $cat })
    if ($sub.Count -eq 0) { continue }
    $md += "## $cat ($(Get-Rate $sub))"
    $md += ""
    $md += "| Case | Pass | ms | SLO | Planner | Failed checks |"
    $md += "|------|------|-----|-----|---------|---------------|"
    foreach ($r in $sub) {
        $failed = @($r.checkResults | Where-Object { -not $_.pass } | ForEach-Object { "$($_.type):$($_.reason)" }) -join "; "
        $md += "| $($r.id) | $(if($r.pass){'PASS'}else{'FAIL'}) | $($r.elapsedMs) | $(if($r.sloPass){'OK'}else{'MISS'}) | $($r.usedPlanner) | $failed |"
    }
    $md += ""
}

$failures = @($results | Where-Object { -not $_.pass })
if ($failures.Count -gt 0) {
    $md += "## Failure patterns"
    $md += ""
    $plannerMis = @($failures | Where-Object { $_.usedPlanner -and $_.category -eq "qa" }).Count
    $toolErr = @($failures | Where-Object { $_.failedSteps.Count -gt 0 }).Count
    $sloMiss = @($failures | Where-Object { -not $_.sloPass }).Count
    $md += "- QA misroute to planner: $plannerMis"
    $md += "- Cases with tool errors: $toolErr"
    $md += "- SLO misses: $sloMiss"
    $md += ""
}

$mdPath = Join-Path $ReportDir "$RunId-report.md"
$md -join "`n" | Set-Content -Path $mdPath -Encoding UTF8

Write-Host ""
Write-Host "JSON=$jsonPath"
Write-Host "MD=$mdPath"
Write-Host "SUMMARY total=$(Get-Rate $results)"

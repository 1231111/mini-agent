param(
    [string]$Base = "http://127.0.0.1:8080",
    [string]$User = "ctxmem_probe",
    [string]$Password = "ProbePass9"
)

$ErrorActionPreference = "Stop"
$enc = New-Object System.Text.UTF8Encoding $false
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$report = New-Object System.Collections.Generic.List[string]

function Add-Line([string]$s) { $script:report.Add($s); Write-Host $s }

function Refresh-Csrf {
    $resp = Invoke-WebRequest "$Base/api/auth-status" -WebSession $session -UseBasicParsing -TimeoutSec 20
    $token = ($session.Cookies.GetCookies($Base) | Where-Object { $_.Name -eq "XSRF-TOKEN" } | Select-Object -First 1).Value
    if (-not $token) {
        $setCookie = $resp.Headers["Set-Cookie"]
        if ($setCookie) {
            $m = [regex]::Match(([string]$setCookie), "XSRF-TOKEN=([^;]+)")
            if ($m.Success) { $token = $m.Groups[1].Value }
        }
    }
    if (-not $token) { throw "no XSRF-TOKEN" }
    $token = [uri]::UnescapeDataString($token)
    $uri = [Uri]$Base
    $session.Cookies.Add($uri, (New-Object System.Net.Cookie("XSRF-TOKEN", $token, "/", $uri.Host)))
    return $token
}

function Auth-Headers([string]$csrf, [string]$jwt) {
    $h = @{
        "X-XSRF-TOKEN" = $csrf
        "Content-Type" = "application/json; charset=utf-8"
    }
    if ($jwt) { $h["Authorization"] = "Bearer $jwt" }
    return $h
}

function Send-Chat([string]$sid, [string]$msg, [string]$csrf, [string]$jwt, [int]$timeoutSec = 180) {
    $body = @{
        sessionId = $sid
        message = $msg
        permissionMode = "accept_edits"
        confirmPolicy = "auto"
    } | ConvertTo-Json -Compress
    $bytes = $enc.GetBytes($body)
    $log = Join-Path $env:TEMP ("probe-" + $sid + ".sse.log")
    $headers = Auth-Headers $csrf $jwt
    try {
        Invoke-WebRequest "$Base/chat/stream" -Method Post -Body $bytes `
            -Headers $headers -WebSession $session -TimeoutSec $timeoutSec `
            -OutFile $log -UseBasicParsing | Out-Null
    } catch {
        Add-Line ("HTTP_ERR " + $sid + " : " + $_.Exception.Message)
    }
    $wait = 0
    while ($wait -lt 40) {
        try {
            $st = Invoke-RestMethod "$Base/api/task-status?sessionId=$sid" `
                -Headers $headers -WebSession $session -TimeoutSec 10
            if (-not $st.data.running) { break }
        } catch { break }
        Start-Sleep -Seconds 2
        $wait += 2
    }
    $sse = if (Test-Path $log) { [IO.File]::ReadAllText($log, $enc) } else { "" }
    $answer = ""
    $last = ""
    foreach ($line in ($sse -split "`n")) {
        $line = $line.TrimEnd("`r")
        if ($line.StartsWith("event:")) { $last = $line.Substring(6).Trim() }
        elseif ($line.StartsWith("data:") -and $last -eq "end") {
            $answer = $line.Substring(5).TrimStart()
        }
    }
    $traces = $null
    try {
        $traces = (Invoke-RestMethod "$Base/api/traces?sessionId=$sid" `
            -Headers $headers -WebSession $session -TimeoutSec 20).data
    } catch { }
    $ctx = $null
    $signals = $null
    $tools = @()
    if ($traces) {
        foreach ($s in $traces) {
            if ($s.stepType -eq "CONTEXT_LOAD" -and $s.content) {
                try { $ctx = $s.content | ConvertFrom-Json } catch { $ctx = $s.content }
            }
            if ($s.stepType -eq "TASK_SIGNALS" -and $s.content) { $signals = [string]$s.content }
            if ($s.toolName) { $tools += [string]$s.toolName }
        }
    }
    return @{
        answer = $answer
        ctx = $ctx
        signals = $signals
        tools = $tools
        traces = $traces
    }
}

$csrf = Refresh-Csrf
$regBody = @{ username = $User; password = $Password; displayName = "probe" } | ConvertTo-Json -Compress
$reg = $null
try {
    $reg = Invoke-RestMethod "$Base/api/register" -Method Post -Body $enc.GetBytes($regBody) `
        -Headers (Auth-Headers $csrf $null) -WebSession $session -TimeoutSec 20
} catch { }
if (-not $reg -or -not $reg.success) {
    $csrf = Refresh-Csrf
    $loginBody = @{ username = $User; password = $Password } | ConvertTo-Json -Compress
    $reg = Invoke-RestMethod "$Base/api/login" -Method Post -Body $enc.GetBytes($loginBody) `
        -Headers (Auth-Headers $csrf $null) -WebSession $session -TimeoutSec 20
}
if (-not $reg.success) { throw ("auth failed: " + ($reg | ConvertTo-Json -Compress)) }
$jwt = [string]$reg.data.token
$userId = [string]$reg.data.user.userId
$tenantId = [string]$reg.data.user.tenantId
$csrf = Refresh-Csrf
Add-Line ("AUTH userId=" + $userId + " tenantId=" + $tenantId + " user=" + $User)

$sidQ = "probe_q_" + (Get-Date -Format "HHmmss")
$sidM = "probe_m_" + (Get-Date -Format "HHmmss")
$sidT = "probe_t_" + (Get-Date -Format "HHmmss")

$msgQ = '你好'
$msgM = '记住这个：我写代码默认使用 Java 21。不要建计划，调用 memory 工具 target=user 保存后只用一句话确认。'
$msgT = '写一份三句话技术方案：如何备份 MySQL。不要创建文件，不要打开浏览器。'

Add-Line ""
Add-Line "=== T1 QUESTION hello ==="
$r1 = Send-Chat $sidQ $msgQ $csrf $jwt 90
Add-Line ("signals=" + $r1.signals)
Add-Line ("ctx=" + ($(if ($r1.ctx) { $r1.ctx | ConvertTo-Json -Compress } else { "null" })))
Add-Line ("tools=" + ($r1.tools -join ","))
Add-Line ("answer=" + $r1.answer.Substring(0, [Math]::Min(180, $r1.answer.Length)))

Add-Line ""
Add-Line "=== T2 remember preference ==="
$r2 = Send-Chat $sidM $msgM $csrf $jwt 180
Add-Line ("signals=" + $r2.signals)
Add-Line ("ctx=" + ($(if ($r2.ctx) { $r2.ctx | ConvertTo-Json -Compress } else { "null" })))
Add-Line ("tools=" + ($r2.tools -join ","))
Add-Line ("answer=" + $r2.answer.Substring(0, [Math]::Min(180, $r2.answer.Length)))

Add-Line ""
Add-Line "=== T3 待办型任务（非轻问答）==="
$r3 = Send-Chat $sidT $msgT $csrf $jwt 180
Add-Line ("signals=" + $r3.signals)
Add-Line ("ctx=" + ($(if ($r3.ctx) { $r3.ctx | ConvertTo-Json -Compress } else { "null" })))
Add-Line ("tools=" + ($r3.tools -join ","))
Add-Line ("answer=" + $r3.answer.Substring(0, [Math]::Min(180, $r3.answer.Length)))

$memRoot = Join-Path $env:USERPROFILE (".miniagent\memory\users\" + $userId)
Add-Line ""
Add-Line ("=== memory files " + $memRoot + " ===")
foreach ($f in @("USER.md", "MEMORY.md", "MIDTERM.md")) {
    $p = Join-Path $memRoot $f
    if (Test-Path $p) {
        $txt = [IO.File]::ReadAllText($p, $enc)
        Add-Line ($f + " bytes=" + $txt.Length + " hasJava21=" + $txt.Contains("Java 21"))
        if ($txt.Length -gt 0 -and $txt.Length -lt 400) { Add-Line $txt }
    } else {
        Add-Line ($f + " MISSING")
    }
}

$fail = 0
function Expect([bool]$ok, [string]$name) {
    if ($ok) { Add-Line ("PASS " + $name) } else { Add-Line ("FAIL " + $name); $script:fail++ }
}

Add-Line ""
Add-Line "=== assertions ==="
$c1 = $r1.ctx
Expect ($c1 -and $c1.injectMemory -eq $false) "T1 injectMemory=false"
Expect ($c1 -and $c1.injectMidterm -eq $false) "T1 injectMidterm=false"
Expect (-not ($r1.tools -contains "memory")) "T1 greeting does not write memory"

$c2 = $r2.ctx
Expect ($r2.tools -contains "memory") "T2 called memory tool"

$c3 = $r3.ctx
Expect ($c3 -and $c3.injectMidterm -eq $false) "T3 injectMidterm=false"
if ($c3 -and $c3.lightTurn -eq $false) {
    Expect ($c3.injectMemory -eq $true) "T3 非轻问答时 injectMemory=true"
} else {
    $seen = if ($c3) { [string]$c3.signals } else { $r3.signals }
    Add-Line ("INFO T3 signals=" + $seen + " lightTurn=true，跳过 injectMemory 断言")
}

$userMd = Join-Path $memRoot "USER.md"
$userBlobHasJava = (Test-Path $userMd) -and ([IO.File]::ReadAllText($userMd, $enc).Contains("Java 21"))
$factHasJava = $false
try {
    $sql = 'SELECT id, tenant_id, scope_id, predicate, object_value FROM agent_semantic_facts WHERE object_value LIKE "%Java 21%" OR subject LIKE "%Java 21%" ORDER BY id DESC LIMIT 8;'
    $factRows = docker exec miniagent-mysql mysql -uroot -pmysql_root_pass mini_agent -N -e $sql 2>$null
    Add-Line "=== facts ==="
    if ($factRows) {
        Add-Line ([string]$factRows)
        $factHasJava = ([string]$factRows).Contains("Java 21")
    } else {
        Add-Line "(no matching facts)"
    }
} catch {
    Add-Line ("facts query skip: " + $_.Exception.Message)
}
if ($r2.tools -contains "memory") {
    Expect ($factHasJava -or (-not $userBlobHasJava)) "T2 no dual USER blob"
    Expect (-not $userBlobHasJava) "T2 USER.md has no Java 21"
}

$mid = Join-Path $memRoot "MIDTERM.md"
if (Test-Path $mid) {
    $mtime = (Get-Item $mid).LastWriteTime
    Expect ($mtime -lt (Get-Date).AddMinutes(-1) -or (Get-Item $mid).Length -eq 0) "MIDTERM not overwritten this round"
}

$out = Join-Path $PSScriptRoot "reports\probe-context-memory.txt"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
[IO.File]::WriteAllLines($out, $report, $enc)
Add-Line ("REPORT " + $out + " fail=" + $fail)
if ($fail -gt 0) { exit 1 }
exit 0

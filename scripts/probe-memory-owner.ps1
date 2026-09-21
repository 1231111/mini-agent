param(
    [string]$Base = "http://127.0.0.1:8080",
    [string]$User = "ctxmem_probe",
    [string]$Password = "ProbePass9"
)

$ErrorActionPreference = "Stop"
$enc = New-Object System.Text.UTF8Encoding $false
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

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
    $h = @{ "X-XSRF-TOKEN" = $csrf; "Content-Type" = "application/json; charset=utf-8" }
    if ($jwt) { $h["Authorization"] = "Bearer $jwt" }
    return $h
}

$csrf = Refresh-Csrf
$loginBody = @{ username = $User; password = $Password } | ConvertTo-Json -Compress
$reg = Invoke-RestMethod "$Base/api/login" -Method Post -Body $enc.GetBytes($loginBody) `
    -Headers (Auth-Headers $csrf $null) -WebSession $session -TimeoutSec 20
if (-not $reg.success) { throw "login failed" }
$jwt = [string]$reg.data.token
$userId = [string]$reg.data.user.userId
$csrf = Refresh-Csrf
Write-Host "AUTH userId=$userId"

$sid = "probe_owner_" + (Get-Date -Format "HHmmss")
$marker = "Java21OwnerRetest-" + (Get-Date -Format "HHmmss")
$msg = "记住这个：我的代码默认使用 $marker。不要建计划，调用 memory 工具 target=user 保存后只用一句话确认。"
$body = @{
    sessionId = $sid
    message = $msg
    permissionMode = "accept_edits"
    confirmPolicy = "auto"
} | ConvertTo-Json -Compress
$log = Join-Path $env:TEMP ("probe-" + $sid + ".sse.log")
Invoke-WebRequest "$Base/chat/stream" -Method Post -Body $enc.GetBytes($body) `
    -Headers (Auth-Headers $csrf $jwt) -WebSession $session -TimeoutSec 180 `
    -OutFile $log -UseBasicParsing | Out-Null

$wait = 0
while ($wait -lt 40) {
    try {
        $st = Invoke-RestMethod "$Base/api/task-status?sessionId=$sid" `
            -Headers (Auth-Headers $csrf $jwt) -WebSession $session -TimeoutSec 10
        if (-not $st.data.running) { break }
    } catch { break }
    Start-Sleep -Seconds 2
    $wait += 2
}

$traces = (Invoke-RestMethod "$Base/api/traces?sessionId=$sid" `
    -Headers (Auth-Headers $csrf $jwt) -WebSession $session -TimeoutSec 20).data
$tools = @()
$ctx = $null
foreach ($s in $traces) {
    if ($s.stepType -eq "CONTEXT_LOAD" -and $s.content) {
        try { $ctx = $s.content | ConvertFrom-Json } catch { $ctx = $s.content }
    }
    if ($s.toolName) { $tools += [string]$s.toolName }
}
Write-Host ("ctx=" + ($(if ($ctx) { $ctx | ConvertTo-Json -Compress } else { "null" })))
Write-Host ("tools=" + ($tools -join ","))
Write-Host ("marker=" + $marker)

$sql = @"
SELECT id, tenant_id, scope_id, predicate, source, object_value
FROM agent_semantic_facts
WHERE object_value LIKE '%$marker%' OR subject LIKE '%$marker%'
ORDER BY id DESC LIMIT 8;
"@
docker exec miniagent-mysql mysql -uroot -pmysql_root_pass --default-character-set=utf8mb4 mini_agent -e $sql 2>$null

$userMd = Join-Path $env:USERPROFILE (".miniagent\memory\users\" + $userId + "\USER.md")
Write-Host ("USER.md exists=" + (Test-Path $userMd))

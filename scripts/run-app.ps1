# Start the MiniAgent backend as a standalone jar (middleware runs natively on the host).
#
#   powershell -ExecutionPolicy Bypass -File scripts\run-app.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\run-app.ps1 -Port 8080
#
# Middleware:
#   Install MySQL on 127.0.0.1:3306 and Redis on 127.0.0.1:6379.
#   This script only probes those ports; it does not start them.
#
# This file is deliberately ASCII-only. PowerShell 5.1 decodes .ps1 files without
# a BOM using the system ANSI code page, so non-ASCII strings would turn into
# mojibake before they ever reach the console. Chinese explanations live in
# scripts/run-app.sh and in the docs instead.
#
# What the script does: validate required variables, warn if middleware is not
# running, then exec java. It never rewrites configuration.

param(
    [int]$Port = 8080,
    [string]$Jar = "mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar"
)

$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $repoRoot

# ---- 1. read middleware credentials from .env ------------------------------
$envFile = Join-Path $repoRoot '.env'
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
}

$missing = @()
foreach ($name in @('MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'MODEL_CONFIG_ENCRYPTION_KEY', 'LLM_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        $missing += $name
    }
}
if ($missing.Count -gt 0) {
    Write-Host ("Missing " + ($missing -join ', ')) -ForegroundColor Red
    Write-Host "MYSQL_ROOT_PASSWORD / REDIS_PASSWORD must match the local MySQL and Redis." -ForegroundColor Red
    Write-Host "MODEL_CONFIG_ENCRYPTION_KEY: 32-byte Base64 (openssl rand -base64 32)." -ForegroundColor Red
    Write-Host "LLM_API_KEY: chat model key; the MIMO_API_KEY value works, but the key name must be LLM_API_KEY." -ForegroundColor Red
    exit 1
}

# ---- 2. build the prod profile environment --------------------------------
$modelsDir = $null
if ($env:MINI_AGENT_MODELS_DIR) {
    $modelsDir = $env:MINI_AGENT_MODELS_DIR
} elseif (Test-Path (Join-Path $repoRoot 'mini-agent-desktop\models\yuan-embedding-2.0-zh.int8.onnx')) {
    $modelsDir = Join-Path $repoRoot 'mini-agent-desktop\models'
} else {
    $modelsDir = Join-Path $HOME '.miniagent\models'
}
$onnx = Join-Path $modelsDir 'yuan-embedding-2.0-zh.int8.onnx'
$tok = Join-Path $modelsDir 'tokenizer.json'
if (-not (Test-Path $onnx) -or -not (Test-Path $tok)) {
    Write-Host "Warning: ONNX model or tokenizer missing under $modelsDir" -ForegroundColor Yellow
    Write-Host "         The app still starts, but semantic search silently degrades." -ForegroundColor Yellow
    Write-Host "         Copy yuan-embedding-2.0-zh.int8.onnx and tokenizer.json there," -ForegroundColor Yellow
    Write-Host "         or point MINI_AGENT_MODELS_DIR at an existing directory." -ForegroundColor Yellow
}

$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:DB_URL = if ($env:DB_URL) { $env:DB_URL } else { 'jdbc:mysql://127.0.0.1:3306/mini_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8' }
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }
$env:DB_PASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { $env:MYSQL_ROOT_PASSWORD }

# application-prod.yml uses host "redis" (the compose service name). The jar runs
# on the host, so that name does not resolve - override it explicitly.
$env:SPRING_DATA_REDIS_HOST = if ($env:SPRING_DATA_REDIS_HOST) { $env:SPRING_DATA_REDIS_HOST } else { '127.0.0.1' }
$env:SPRING_DATA_REDIS_PORT = if ($env:SPRING_DATA_REDIS_PORT) { $env:SPRING_DATA_REDIS_PORT } else { '6379' }

$env:ALLOWED_ORIGINS = if ($env:ALLOWED_ORIGINS) { $env:ALLOWED_ORIGINS } else { 'https://localhost' }

# Local accounts. The prod profile disables registration, so the first account
# must come from BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD (min 16 chars).
# Set ACCOUNT_BASE_URL=http://<host>:8081 to delegate to the account service instead.
if (-not $env:ACCOUNT_BASE_URL) { $env:ACCOUNT_BASE_URL = '' }

$env:AGENT_MEMORY_VECTOR_BACKEND = if ($env:AGENT_MEMORY_VECTOR_BACKEND) { $env:AGENT_MEMORY_VECTOR_BACKEND } else { 'local' }
$env:AGENT_CODEBASE_EMBEDDING_PROVIDER = if ($env:AGENT_CODEBASE_EMBEDDING_PROVIDER) { $env:AGENT_CODEBASE_EMBEDDING_PROVIDER } else { 'local-onnx' }
$env:MINI_AGENT_MODELS_DIR = $modelsDir
if (-not $env:MINI_AGENT_HOME) { $env:MINI_AGENT_HOME = Join-Path $HOME '.miniagent' }

# ---- 3. verify jar and middleware -----------------------------------------
$jarPath = Join-Path $repoRoot $Jar
if (-not (Test-Path $jarPath)) {
    Write-Host "Jar not found: $jarPath" -ForegroundColor Red
    Write-Host "Build it first: .\mvnw.cmd package -pl mini-agent-app -am -DskipTests" -ForegroundColor Red
    exit 1
}

# Port probe instead of a container lookup: the deployment shape is a jar on the
# host, so the only thing worth checking is whether something listens on 3306/6379.
foreach ($probe in @(@{ Port = 3306; Name = 'mysql' }, @{ Port = 6379; Name = 'redis' })) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $connected = $client.ConnectAsync('127.0.0.1', $probe.Port).Wait(1000)
    } catch {
        $connected = $false
    } finally {
        $client.Dispose()
    }
    if (-not $connected) {
        Write-Host "Warning: $($probe.Name) is not listening on 127.0.0.1:$($probe.Port)." -ForegroundColor Yellow
        Write-Host "         The prod profile refuses to start without it." -ForegroundColor Yellow
    }
}

# ---- 4. start -------------------------------------------------------------
Write-Host "Starting MiniAgent: profile=prod port=$Port" -ForegroundColor Cyan
Write-Host "  data dir : $env:MINI_AGENT_HOME"
Write-Host "  models   : $modelsDir"

& java -jar $jarPath --server.port=$Port
exit $LASTEXITCODE

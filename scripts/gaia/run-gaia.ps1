<#
.SYNOPSIS
  一键跑 GAIA 并判分（自动找解释器、检查数据、跑完给报告）。

.DESCRIPTION
  它做的事：
    1) 找到带 requests 的 Python（默认优先用 conda 环境 bert_train）；
    2) 检查用例文件是否存在（不在就告诉你去跑 fetch-gaia.py，而不是报个看不懂的错）；
    3) 调用 run-gaia.py（连真实 agent 服务，或 --SelfTest 用本地桩服务验脚本自身）；
    4) 把退出码透出去，可以直接当 CI 门禁。

.EXAMPLE
  # ① 先验脚本自身（不连服务、不需要模型 key，几秒钟）
  .\run-gaia.ps1 -ScoreTest
  .\run-gaia.ps1 -SelfTest

  # ② 快速冒烟：每级 3 题（默认）
  .\run-gaia.ps1 -BaseUrl http://127.0.0.1:8080 -User admin -Password '***'

  # ③ 全量 validation + 门禁：通过率低于 30% 就返回非 0
  .\run-gaia.ps1 -User admin -Password '***' -All -MinPassRate 0.3
#>
[CmdletBinding()]
param(
    [string]   $BaseUrl     = 'http://127.0.0.1:8080',
    [string]   $User        = '',
    [string]   $Password    = '',
    [int]      $Limit       = 3,
    [switch]   $All,
    [int[]]    $Level       = @(),
    [int]      $TimeoutSec  = 300,
    [double]   $MinPassRate = 0.0,
    [switch]   $SelfTest,
    [switch]   $ScoreTest,
    [string]   $Python      = '',
    [string]   $Cases       = '',
    [string]   $Out         = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # scripts/gaia -> scripts -> 仓库根
$runner   = Join-Path $PSScriptRoot 'run-gaia.py'
if (-not (Test-Path $runner)) { throw "找不到 $runner" }

# 用例与报告的默认位置要同时兼容两种目录布局：
#   仓库布局：<root>\scripts\gaia\run-gaia.ps1  → 用例在 <root>\scripts\agent-eval\gaia\
#   拷贝布局：<某目录>\tools\run-gaia.ps1       → 用例在同级的 <某目录>\
# 只认仓库布局的话，把脚本连同数据拷到别处（例如桌面）就找不到用例了。
$parentDir = Split-Path -Parent $PSScriptRoot
$repoCases = Join-Path $repoRoot 'scripts\agent-eval\gaia\cases-gaia-validation.json'
$nearCases = Join-Path $parentDir 'cases-gaia-validation.json'
if (Test-Path $repoCases) {
    $defaultCases = $repoCases
    $defaultOut   = Join-Path $repoRoot 'scripts\agent-eval\gaia\report'
} elseif (Test-Path $nearCases) {
    $defaultCases = $nearCases
    $defaultOut   = Join-Path $parentDir 'report'
} else {
    $defaultCases = $repoCases
    $defaultOut   = Join-Path $repoRoot 'scripts\agent-eval\gaia\report'
}

function Find-Python {
    param([string]$Explicit)
    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "指定的 Python 不存在: $Explicit" }
        return $Explicit
    }
    $candidates = @(
        "$env:USERPROFILE\miniconda3\envs\bert_train\python.exe",
        "$env:LOCALAPPDATA\miniconda3\envs\bert_train\python.exe"
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    foreach ($name in @('python', 'python3', 'py')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    throw "找不到 Python。请用 -Python <path> 指定（需要 requests 包）。"
}

$py = Find-Python -Explicit $Python
Write-Host "解释器 : $py"

# 依赖自检：缺 requests 时给出可操作的提示，而不是让脚本在后面崩
& $py -c "import requests" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] 该解释器没有 requests。改用带依赖的那个，例如：" -ForegroundColor Red
    Write-Host "  -Python `"$env:USERPROFILE\miniconda3\envs\bert_train\python.exe`""
    exit 2
}

$casesPath = if ($Cases) { $Cases } else { $defaultCases }
if (-not $ScoreTest) {
    if (-not (Test-Path $casesPath)) {
        Write-Host "[FAIL] 找不到用例文件: $casesPath" -ForegroundColor Red
        Write-Host "先下载并转换（GAIA 是 gated 数据集，需要 HF token）："
        Write-Host "  `$env:HF_TOKEN = 'hf_xxx'"
        Write-Host "  & `"$py`" scripts/gaia/fetch-gaia.py"
        exit 2
    }
    $caseCount = (Get-Content $casesPath -Raw -Encoding UTF8 | ConvertFrom-Json).Count
    Write-Host "用例   : $casesPath（$caseCount 条）"
}

if (-not $Out) { $Out = $defaultOut }

$argv = @($runner, '--cases', $casesPath, '--out', $Out, '--timeout', "$TimeoutSec")
if ($ScoreTest) {
    $argv += '--score-test'
} elseif ($SelfTest) {
    $argv += @('--self-test', '--limit', "$Limit")
} else {
    $argv += @('--base-url', $BaseUrl, '--limit', "$Limit")
    if ($All) { $argv += '--all' }
    if ($Level.Count -gt 0) { foreach ($l in $Level) { $argv += @('--level', "$l") } }
    if ($MinPassRate -gt 0) { $argv += @('--min-pass-rate', "$MinPassRate") }
    if (-not $User) { $User = Read-Host '用户名' }
    if (-not $Password) {
        $sec = Read-Host '密码' -AsSecureString
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    }
    $argv += @('-u', $User, '-p', $Password)
}

Write-Host "动作   : $(if ($ScoreTest) { '判分自检' } elseif ($SelfTest) { '端到端自测（本地桩服务）' } else { "跑 $BaseUrl" })"
Write-Host ('-' * 72)

& $py @argv
$code = $LASTEXITCODE

Write-Host ('-' * 72)
if ($ScoreTest -or $SelfTest) {
    if ($code -eq 0) { Write-Host "[OK] 脚本自身可信（可以放心用它的分数）" -ForegroundColor Green }
    else { Write-Host "[FAIL] 脚本自检未通过，别拿它的分数当结论" -ForegroundColor Red }
} else {
    $reportPath = [IO.Path]::ChangeExtension($Out, '.json')
    if (Test-Path $reportPath) { Write-Host "报告: $reportPath" }
    if ($code -eq 0) { Write-Host "[OK] 完成" -ForegroundColor Green }
    else { Write-Host "[FAIL] 未达门槛或发生错误（退出码 $code）" -ForegroundColor Red }
}
exit $code

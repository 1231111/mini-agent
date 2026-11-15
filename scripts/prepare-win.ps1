<#
.SYNOPSIS
    MiniAgent Windows 打包环境准备（幂等）。

.DESCRIPTION
    只做「让 build-desktop.mjs 能跑起来」所需的环境准备，不参与构建逻辑：
      1. Node.js   —— 只检测，缺了给出下载地址
      2. JDK 21    —— 检测 → 缺失则下载解压到 %USERPROFILE%\.jdks → 写用户级 JAVA_HOME
      3. Maven 镜像 —— ~/.m2/settings.xml 不存在时才写；已存在一律不覆盖
      4. 物料自检  —— fat jar / jre / ffmpeg / Chromium / 模型，缺什么列什么

    为什么单独一个脚本：环境是「一台机器装一次」的事，构建逻辑是「每次跑」的事。
    把 JDK 探测、镜像、下载塞进 build-desktop.mjs 会让它带上一堆机器相关的猜测，
    而构建入口只需要认 JAVA_HOME —— 找不到就明确报错。两者分开，各自都简单。

    幂等：已经就绪的机器上只做检测，不下载、不写配置，几百毫秒返回。

.PARAMETER NoInstall
    只检测不安装：不改任何系统配置、不下载任何东西。用于排查和 CI 预检。

.PARAMETER EmitEnv
    准备完成后只输出一行 JDK 路径（供 package-win.bat 捕获），不打印报告。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\prepare-win.ps1

.EXAMPLE
    # 排查用：只告诉我缺什么，别动手
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\prepare-win.ps1 -NoInstall
#>
[CmdletBinding()]
param(
    [switch]$NoInstall,
    [switch]$EmitEnv
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# 输出编码对齐。PS 5.1 默认按 OEM 码页（中文系统 936）写 stdout，而调用它的终端往往
# 按 UTF-8 解码（同一个窗口里 node 的中文是正常的），不对齐的话本脚本的中文全是乱码。
# 设置 Console.OutputEncoding 同时会改掉控制台代码页，两边一致才不会花字。
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$JdkMajor   = 21
# 华为云镜像实测 2.4 MB/s；GitHub release 直连只有 30~40 KB/s，不可用。
$JdkUrl     = 'https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_windows-x64_bin.zip'
$JdkRoot    = Join-Path $env:USERPROFILE '.jdks'
$EnvFile    = Join-Path $env:TEMP 'miniagent-jdk-path.txt'

# 目录名必须是「版本号以 21 开头」：jdk-11.0.21（JDK 11 也自带 jlink）和
# jdk-1.8.0_211 都要排除，否则会挑到一个不能用来 jlink 的旧 JDK。
$JdkNamePattern = '(?:^|[-_]|jdk)21(?:[._-]|$)'

$problems = 0
$warnings = 0

function Write-Step($text)    { if (-not $EmitEnv) { Write-Host "`n$text" -ForegroundColor Cyan } }
function Write-Ok($text)      { if (-not $EmitEnv) { Write-Host "  [OK]   $text" -ForegroundColor Green } }
function Write-Info($text)    { if (-not $EmitEnv) { Write-Host "         $text" -ForegroundColor Gray } }
function Write-Missing($text) { if (-not $EmitEnv) { Write-Host "  [缺失] $text" -ForegroundColor Yellow } }
function Write-Warn($text)    { if (-not $EmitEnv) { Write-Host "  [注意] $text" -ForegroundColor Yellow } }
function Write-Fatal($text)   { if (-not $EmitEnv) { Write-Host "  [错误] $text" -ForegroundColor Red } }

function Test-JdkHome {
    param([string]$JdkHome)
    if ([string]::IsNullOrWhiteSpace($JdkHome)) { return $false }
    return Test-Path (Join-Path $JdkHome 'bin\jlink.exe')
}

function Get-JdkMajor {
    param([string]$JdkHome)
    $exe = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path $exe)) { return 0 }
    $r = Invoke-Native $exe @('-version')
    if ($r.Output -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

<#
    调用原生命令，拿「输出 + 退出码」。

    必须包这一层：`java -version`、`curl`、`tar` 都把正常信息写到 stderr，
    而脚本开头把 $ErrorActionPreference 设成了 Stop —— PS 5.1 会把原生命令的
    stderr 当成 NativeCommandError 抛出，`2>&1` 也拦不住。于是 `java -version`
    这种最平常的探测就能让整个脚本失败。
#>
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& $Exe @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return @{ Output = $out; Code = $code }
}

# 只收 JDK 21 且带 jlink 的。目录名不符或没有 jlink 的直接跳过。
function Find-InstalledJdk {
    $roots = @(
        $JdkRoot,
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Microsoft'),
        (Join-Path $env:ProgramFiles 'Zulu'),
        (Join-Path $env:ProgramFiles 'Amazon Corretto')
    )

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $dirs = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match $JdkNamePattern } |
                Sort-Object Name -Descending
        foreach ($dir in $dirs) {
            if (Test-JdkHome $dir.FullName) { return $dir.FullName }
        }
    }
    return $null
}

function Resolve-Jdk {
    # 顺序：JAVA_HOME → PATH 上的 java 反推 → 已知安装位置。
    # 显式配置永远优先，猜出来的只做兜底。
    if ($env:JAVA_HOME -and (Test-JdkHome $env:JAVA_HOME)) {
        $major = Get-JdkMajor $env:JAVA_HOME
        if ($major -eq $JdkMajor) { return @{ Home = $env:JAVA_HOME; From = 'JAVA_HOME' } }
        Write-Warn "JAVA_HOME 指向 JDK $major（$env:JAVA_HOME），项目需要 JDK $JdkMajor，继续找其它安装"
        $warnings++
    }

    # PATH 上可能有 Windows 应用执行别名（%LOCALAPPDATA%\Microsoft\WindowsApps\java.exe），
    # 那是个空壳：Source 拿得到、Target 解析不出来。所以每一步都要判空再往下走。
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source) {
        $real = $cmd.Source
        try {
            $target = (Get-Item $cmd.Source -ErrorAction Stop).Target
            if ($target) { $real = $target }
        } catch { }
        if ($real) {
            $JdkHome = Split-Path -Parent (Split-Path -Parent $real)
            if ($JdkHome -and (Test-JdkHome $JdkHome) -and ((Get-JdkMajor $JdkHome) -eq $JdkMajor)) {
                return @{ Home = $JdkHome; From = 'PATH' }
            }
        }
    }

    $found = Find-InstalledJdk
    if ($found) { return @{ Home = $found; From = '已安装目录' } }

    return $null
}

function Install-Jdk {
    Write-Info "JDK $JdkMajor 未找到，从华为云镜像下载（约 192 MB，免管理员权限）..."

    New-Item -ItemType Directory -Force -Path $JdkRoot | Out-Null
    $zip = Join-Path $env:TEMP 'miniagent-jdk21.zip'

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        $r = Invoke-Native $curl.Source @('-L', '--retry', '3', '-o', $zip, $JdkUrl)
        if ($r.Code -ne 0) { throw "下载失败（curl 退出码 $($r.Code)）：$JdkUrl" }
    } else {
        Invoke-WebRequest -Uri $JdkUrl -OutFile $zip -UseBasicParsing
    }

    if (-not (Test-Path $zip) -or (Get-Item $zip).Length -lt 50MB) {
        throw "下载的压缩包不完整：$zip"
    }

    # Win10 1809+ 自带 bsdtar，解 192MB 的 zip 比 Expand-Archive 快一个量级。
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($tar) {
        $r = Invoke-Native $tar.Source @('-xf', $zip, '-C', $JdkRoot)
        if ($r.Code -ne 0) { throw "解压失败（tar 退出码 $($r.Code)）：$zip" }
    } else {
        Expand-Archive -Path $zip -DestinationPath $JdkRoot -Force
    }
    Remove-Item $zip -Force -ErrorAction SilentlyContinue

    $found = Find-InstalledJdk
    if (-not $found) { throw "解压后仍找不到含 jlink 的 JDK $JdkMajor，请检查 $JdkRoot" }
    return $found
}

function Set-JavaHomePersistent {
    param([string]$JdkHome)

    $current = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if ($current -eq $JdkHome) {
        Write-Ok "用户级 JAVA_HOME 已是 $JdkHome"
    } else {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $JdkHome, 'User')
        Write-Ok "已写入用户级 JAVA_HOME = $JdkHome"
    }
    # 当前进程也设上，方便在同一个窗口里继续跑构建。
    $env:JAVA_HOME = $JdkHome
}

function New-MavenMirror {
    Write-Step "[3/4] Maven 镜像"
    $m2 = Join-Path $env:USERPROFILE '.m2'
    $settings = Join-Path $m2 'settings.xml'

    if (Test-Path $settings) {
        $content = Get-Content $settings -Raw -ErrorAction SilentlyContinue
        if ($content -match 'aliyun|mirrorOf') {
            Write-Ok "已存在 settings.xml 且配了镜像，跳过"
        } else {
            Write-Warn "已存在 settings.xml 但没配镜像，不覆盖（需要加速请自行加 mirror）"
        }
        return
    }

    if ($NoInstall) {
        Write-Missing "没有 ~/.m2/settings.xml（构建会走 Maven Central，国内较慢）"
        return
    }

    New-Item -ItemType Directory -Force -Path $m2 | Out-Null
    $xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <!-- 由 scripts/prepare-win.ps1 生成，只为加速首次拉依赖。删除本文件即回到 Maven Central。 -->
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <name>Aliyun Public Repository</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
'@
    Set-Content -Path $settings -Value $xml -Encoding UTF8
    Write-Ok "已写入 $settings（central → 阿里云）"
}

function Test-Materials {
    Write-Step "[4/4] 物料自检（模型缺失会让构建在第 5 步硬失败）"

    $jar = Join-Path $RepoRoot 'mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar'
    if (Test-Path $jar) {
        Write-Ok ("fat jar  {0:N1} MB" -f ((Get-Item $jar).Length / 1MB))
    } else {
        Write-Missing 'fat jar 未打包（构建第 1 步会自动生成）'
    }

    $jre = Join-Path $RepoRoot 'mini-agent-desktop\jre\bin\java.exe'
    if (Test-Path $jre) { Write-Ok 'jre（构建第 2 步会复用）' }
    else { Write-Missing 'jre 未裁剪（构建第 2 步会自动生成）' }

    $ffmpeg = Join-Path $RepoRoot 'mini-agent-desktop\ffmpeg\ffmpeg.dll'
    if (Test-Path $ffmpeg) { Write-Ok 'ffmpeg（构建第 3 步会复用）' }
    else { Write-Missing 'ffmpeg 未下载（构建第 3 步会自动下载）' }

    $browsers = Join-Path $RepoRoot 'mini-agent-desktop\browsers'
    if (Test-Path $browsers) {
        $shell = Get-ChildItem -Path $browsers -Directory -Filter 'chromium_headless_shell-*' -ErrorAction SilentlyContinue
        if ($shell) { Write-Ok "Chromium 物料 $($shell[0].Name)" }
        else { Write-Missing 'Chromium 物料不完整（构建第 4 步会自动下载约 195 MB）' }
    } else {
        Write-Warn 'Chromium 未准备，首次构建会自动下载约 195 MB（国内很慢，可设 PLAYWRIGHT_DOWNLOAD_HOST 走镜像）'
    }

    # 模型是硬依赖：build-desktop.mjs 缺了会直接失败，不产出「能装但没有语义检索」的包。
    $modelDir  = Join-Path $RepoRoot '.verify\onnx'
    $modelFile = Join-Path $modelDir 'yuan-embedding-2.0-zh.int8.onnx'
    $tokenFile = Join-Path $modelDir 'tokenizer.json'
    $modelOk   = $false
    $tokenOk   = $false

    # 期望字节数从 scripts/lib/model-manifest.mjs 现读，不在这里再写一遍 ——
    # 两处定义等于改一处漏一处，会造出「准备脚本放行、构建拦住」的自相矛盾状态。
    $expectedModel = 0
    $expectedToken = 0
    $manifestPath  = Join-Path $RepoRoot 'scripts\lib\model-manifest.mjs'
    if (Test-Path $manifestPath) {
        $src = Get-Content $manifestPath -Raw
        if ($src -match 'int8:\s*\{[^}]*?bytes:\s*(\d+)') { $expectedModel = [int64]$Matches[1] }
        if ($src -match 'TOKENIZER_BYTES\s*=\s*(\d+)') { $expectedToken = [int64]$Matches[1] }
    }

    if (Test-Path $modelFile) {
        $bytes = (Get-Item $modelFile).Length
        # 字节数是校验值不是参考值：截断的模型能通过存在性检查，却会让检索静默失效。
        if ($expectedModel -gt 0 -and $bytes -eq $expectedModel) {
            Write-Ok ('embedding 模型 int8.onnx（{0:N0} B）' -f $bytes); $modelOk = $true
        } else {
            Write-Missing ('embedding 模型字节数不符（{0:N0} B，应为 {1:N0} B）' -f $bytes, $expectedModel)
        }
    } else {
        Write-Missing 'embedding 模型 int8.onnx 不存在'
    }

    # tokenizer 的取值顺序必须与 build-desktop.mjs 的 resolveModelSources 一致：
    # 先看物料目录自带的那份，没有再退回 HF 模型目录。只看前者会误报「缺失」。
    $tokenCandidates = @(
        $tokenFile,
        (Join-Path $RepoRoot 'embedding-server\models\models\IEITYuan--Yuan-embedding-2.0-zh\snapshots\master\tokenizer.json')
    )
    $tokenFound = $tokenCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

    if ($tokenFound) {
        $bytes = (Get-Item $tokenFound).Length
        if ($expectedToken -gt 0 -and $bytes -eq $expectedToken) {
            $from = if ($tokenFound -eq $tokenFile) { '物料目录' } else { 'HF 模型目录兜底' }
            Write-Ok ('tokenizer.json（{0:N0} B，来自{1}）' -f $bytes, $from); $tokenOk = $true
        } else {
            Write-Missing ('tokenizer.json 字节数不符（{0:N0} B，应为 {1:N0} B）' -f $bytes, $expectedToken)
        }
    } else {
        Write-Missing 'tokenizer.json 不存在（物料目录与 HF 模型目录都没有）'
    }

    if (-not ($modelOk -and $tokenOk)) {
        Write-Host ''
        Write-Host '  模型物料补齐方式（二选一）：' -ForegroundColor Yellow
        Write-Host '    A. 本机导出：python .verify\onnx\export_onnx.py --variants' -ForegroundColor Gray
        Write-Host '       原模型要放在 embedding-server\models\models\IEITYuan--Yuan-embedding-2.0-zh\snapshots\master' -ForegroundColor Gray
        Write-Host '       （依赖 torch + onnxruntime + sentence-transformers==3.4.1，峰值内存约 6~8 GB）' -ForegroundColor Gray
        Write-Host '       注意：ModelScope 那份仓库缺 1_Pooling/ 与 2_Dense/，要从 HF 补（hf-mirror.com 可直连）' -ForegroundColor Gray
        Write-Host '    B. 从已备好物料的机器拷 .verify\onnx 目录过来' -ForegroundColor Gray
        Write-Host '  注意：构建会在这里硬失败，这是设计如此，不要用跳过的方式绕。' -ForegroundColor Yellow
    }
}

# ───────────────────────────── 主流程 ─────────────────────────────

try {
    Write-Step "[1/4] Node.js"

    $node = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($node) {
        $ver = (Invoke-Native $node.Source @('--version')).Output.Trim()
        Write-Ok "Node.js $ver"
    } else {
        Write-Fatal 'Node.js 未安装。装 18+ 后重试：https://nodejs.org/ （或 winget install OpenJS.NodeJS.LTS）'
        exit 1
    }

    Write-Step "[2/4] JDK $JdkMajor"
    $jdk = Resolve-Jdk
    if ($jdk) {
        Write-Ok "JDK $JdkMajor：$($jdk.Home)（来源：$($jdk.From)）"
    } else {
        if ($NoInstall) {
            Write-Missing "JDK $JdkMajor 未找到（-NoInstall 模式，不自动安装）"
            exit 1
        }
        $JdkHome = Install-Jdk
        $jdk = @{ Home = $JdkHome; From = '本次安装' }
        Write-Ok "JDK $JdkMajor：$JdkHome"
    }

    if ($NoInstall) {
        if ($env:JAVA_HOME -ne $jdk.Home) {
            Write-Warn "当前进程 JAVA_HOME 未指向 $($jdk.Home)（-NoInstall 模式，不做修改）"
        }
    } else {
        Set-JavaHomePersistent $jdk.Home
    }

    New-MavenMirror
    Test-Materials

    # 写给 package-win.bat 用：子进程里 setx 出来的变量父进程看不到，必须显式传递。
    # 用系统 ANSI 编码（而不是 UTF-8）：cmd 的 set /p 按当前的 OEM/ANSI 码页读字节，
    # 写成 UTF-8 时，路径里一旦有中文就会变成乱码。
    [System.IO.File]::WriteAllText($EnvFile, $jdk.Home, [System.Text.Encoding]::Default)

    if ($EmitEnv) {
        Write-Output $jdk.Home
        exit 0
    }

    Write-Host ''
    if ($warnings -gt 0) {
        Write-Host "准备完成（$warnings 条注意）。现在可以在**新开的**终端里跑 package-win.bat。" -ForegroundColor Yellow
    } else {
        Write-Host '准备完成。现在可以在**新开的**终端里跑 package-win.bat。' -ForegroundColor Green
    }
    exit 0
}
catch {
    Write-Fatal $_.Exception.Message
    exit 1
}

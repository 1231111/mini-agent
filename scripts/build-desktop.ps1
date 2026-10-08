<#
  构建 MiniAgent 桌面客户端（出厂形态）

  三步：
    1. mvnw package       —— 打 fat jar（mini-agent-app 的 spring-boot-maven-plugin 已配好 repackage）
    2. jlink              —— 从 JDK 裁一个最小 JRE 到 mini-agent-desktop/jre
    3. electron-builder   —— 打便携 exe

  产物：mini-agent-desktop/dist/

  为什么需要内置 JRE：
    main.js 原先 spawn mvnw spring-boot:run，要求客户机装 Maven/JDK 并联网解析依赖。
    出厂形态下这是装机失败率 100% 的根因。内置 JRE 后只依赖电子的 java.exe。

  用法：
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop.ps1
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop.ps1 -SkipJar   # 复用已打好的 jar
    powershell -ExecutionPolicy Bypass -File scripts\build-desktop.ps1 -SkipJre   # 复用已有 jre
#>
param(
    [switch]$SkipJar,
    [switch]$SkipJre,
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'

$repoRoot   = Resolve-Path (Join-Path $PSScriptRoot '..')
$desktopDir = Join-Path $repoRoot 'mini-agent-desktop'
$jreDir     = Join-Path $desktopDir 'jre'
$jarPath    = Join-Path $repoRoot 'mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar'

# ── 1. fat jar ────────────────────────────────────────────────
if (-not $SkipJar) {
    Write-Host '[1/3] 打包 fat jar ...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & .\mvnw.cmd -q package -pl mini-agent-app -am -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "maven 打包失败，退出码 $LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not (Test-Path $jarPath)) { throw "找不到 fat jar：$jarPath" }
Write-Host ("      jar: {0:N1} MB" -f ((Get-Item $jarPath).Length / 1MB))

# ── 2. jlink ──────────────────────────────────────────────────
if (-not $SkipJre) {
    Write-Host '[2/3] 用 jlink 裁剪 JRE ...' -ForegroundColor Cyan

    $jdkHome = $env:JAVA_HOME
    if (-not $jdkHome) {
        # JAVA_HOME 没设就从当前 java 可执行文件反推（...\bin\java.exe -> 上一级）
        $javaExe = (Get-Command java).Source
        $jdkHome = Split-Path (Split-Path $javaExe -Parent) -Parent
    }
    $jlink = Join-Path $jdkHome 'bin\jlink.exe'
    if (-not (Test-Path $jlink)) {
        throw "找不到 jlink：$jlink`n需要完整 JDK（含 jlink），JRE 不行。请设置 JAVA_HOME 指向 JDK 21。"
    }
    Write-Host "      JDK: $jdkHome"

    # 模块清单说明（这份列表是保守超集，多带一个模块通常只有几百 KB）：
    #   java.net.http    —— langchain4j-http-client-jdk 走 JDK HttpClient
    #   jdk.crypto.ec    —— HTTPS 的 ECDHE 密钥交换，缺了所有 https 调用都握手失败
    #   jdk.unsupported  —— sun.misc.Unsafe，Netty/Lombok/若干字节码库依赖
    #   java.naming      —— Spring 的 JNDI 支持
    #   java.instrument  —— Spring 的 LoadTimeWeaver
    #   java.sql + rowset—— JPA/JDBC
    #   java.desktop     —— java.awt 相关（无头模式下部分图像/字体 API 仍会引用）
    $modules = @(
        'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging',
        'java.management', 'java.naming', 'java.net.http', 'java.prefs', 'java.rmi',
        'java.scripting', 'java.security.jgss', 'java.security.sasl', 'java.sql',
        'java.sql.rowset', 'java.transaction.xa', 'java.xml', 'java.xml.crypto',
        'jdk.crypto.cryptoki', 'jdk.crypto.ec', 'jdk.httpserver', 'jdk.jfr',
        'jdk.management', 'jdk.unsupported', 'jdk.unsupported.desktop', 'jdk.zipfs'
    ) -join ','

    if (Test-Path $jreDir) { Remove-Item -Recurse -Force $jreDir }
    & $jlink --add-modules $modules `
             --strip-debug `
             --no-header-files `
             --no-man-pages `
             --compress=zip-6 `
             --output $jreDir
    if ($LASTEXITCODE -ne 0) { throw "jlink 失败，退出码 $LASTEXITCODE" }

    $bytes = (Get-ChildItem $jreDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-Host ("      JRE: {0:N1} MB" -f ($bytes / 1MB))
} else {
    if (-not (Test-Path $jreDir)) { throw "-SkipJre 但 $jreDir 不存在" }
}

# ── 3. electron-builder ───────────────────────────────────────
if (-not $SkipPackage) {
    Write-Host '[3/3] 打包便携 exe ...' -ForegroundColor Cyan
    Push-Location $desktopDir
    try {
        if (-not (Test-Path (Join-Path $desktopDir 'node_modules'))) {
            Write-Host '      node_modules 不存在，先 npm install'
            & npm install
            if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' }
        }
        & npm run build:win
        if ($LASTEXITCODE -ne 0) { throw 'electron-builder 失败' }
    } finally { Pop-Location }
}

Write-Host ''
Write-Host '完成。产物在 mini-agent-desktop/dist/' -ForegroundColor Green

<#
.SYNOPSIS
  检查已打好的 fat jar 里的迁移是否干净（版本唯一 + 没有源码里已删除的幽灵文件）。

.DESCRIPTION
  为什么要在**打包产物**上再查一遍，而不是只靠单测：
    1. 打包脚本用的是 `mvn package`（不带 clean）—— Maven 不会删除 target/classes 里
       已从源码移除的资源，删过/改名的迁移会一直躺在那里，被打进 jar；
    2. 打包脚本带 `-DskipTests`，单测（MigrationVersionUniquenessTest）根本不会跑。
  于是"源码干净、jar 脏"是真实存在的组合，而且只有启动时才炸：
     FlywayException: Found more than one migration with version 9

  本脚本检查两件事：
    A. 版本号唯一（跨 BOOT-INF/classes/db/migration 的 SQL 与 com/miniagent/migration 的 Java 类）；
    B. 没有幽灵迁移 —— jar 里的每个迁移都能在源码树里找到对应文件。

.PARAMETER Jar
  fat jar 路径。默认 mini-agent-app/target 下的 SNAPSHOT jar。

.EXAMPLE
  .\scripts\check-jar-migrations.ps1
  .\scripts\check-jar-migrations.ps1 -Jar .\mini-agent-desktop\dist\win-unpacked\resources\mini-agent-app.jar
#>
[CmdletBinding()]
param(
    [string] $Jar = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Jar) {
    $Jar = Join-Path $repoRoot 'mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar'
}
if (-not (Test-Path $Jar)) { throw "找不到 jar: $Jar" }
$Jar = (Resolve-Path $Jar).Path

$sqlSrcDir = Join-Path $repoRoot 'mini-agent-app\src\main\resources\db\migration'
$javaSrcDir = Join-Path $repoRoot 'mini-agent-app\src\main\java\com\miniagent\migration'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
$nestedEntries = @()
try {
    $sqlEntries = @($zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/classes/db/migration/V[^/]+\.sql$' })
    $javaEntries = @($zip.Entries | Where-Object {
        $_.FullName -match '^BOOT-INF/classes/com/miniagent/migration/V[^/]+\.class$' -and $_.Name -notmatch '\$'
    })

    # 嵌套依赖 jar 也要查：Flyway 扫的是整个 classpath，被依赖模块自带的迁移同样参与版本号竞争。
    # 真实事故（2026-10-29）：mini-agent-memory 带了 db/migration/V1__procedure_settlement_schema.sql，
    # 作为依赖进 fat jar 后与 app 的 V1__baseline.sql 撞版本，启动即
    #   FlywayException: Found more than one migration with version 1
    # 当时这个脚本只查 BOOT-INF/classes，于是报了 [OK] —— 闸门自己漏了。
    foreach ($lib in @($zip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/[^/]+\.jar$' })) {
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ("chk-" + [guid]::NewGuid().ToString('N') + '.jar')
        try {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($lib, $tmp, $true)
            $inner = [System.IO.Compression.ZipFile]::OpenRead($tmp)
            foreach ($e in $inner.Entries) {
                # 归一化分隔符：Maven 打的 jar 用 '/'，但用 CreateFromDirectory 等工具打的 jar
                # 在 Windows 上会写成 '\'。只认 '/' 的话闸门会静默漏掉整个依赖 jar。
                $entryPath = $e.FullName.Replace('\', '/')
                if ($entryPath -match '^db/migration/.+\.sql$') {
                    $nestedEntries += [pscustomobject]@{ Name = ($entryPath -split '/')[-1]; FullName = $entryPath; Lib = $lib.Name }
                }
            }
            $inner.Dispose()
        } catch {
            Write-Host "[WARN] 无法读取嵌套 jar: $($lib.Name)" -ForegroundColor Yellow
        } finally {
            Remove-Item $tmp -Force -ErrorAction SilentlyContinue
        }
    }
} finally {
    $zip.Dispose()
}

Write-Host "jar   : $Jar"
Write-Host "SQL   : $($sqlEntries.Count) 个    Java: $($javaEntries.Count) 个    依赖 jar 内 SQL: $($nestedEntries.Count) 个"
foreach ($n in $nestedEntries) { Write-Host "  依赖迁移: $($n.Lib)!/$($n.FullName)" }

$problems = New-Object System.Collections.Generic.List[string]

# ── A. 版本唯一（含依赖 jar 里的迁移）────────────────────────
$byVersion = @{}
$allEntries = @($sqlEntries) + @($javaEntries) + @($nestedEntries)
foreach ($e in $allEntries) {
    $name = $e.Name
    if ($name -notmatch '^V(\d+(?:\.\d+)*)__') {
        $problems.Add("无法解析版本号: $name")
        continue
    }
    $version = $Matches[1]
    if (-not $byVersion.ContainsKey($version)) { $byVersion[$version] = @() }
    $label = if ($e.PSObject.Properties.Name -contains 'Lib') { "$name（来自依赖 $($e.Lib)）" } else { $name }
    $byVersion[$version] += $label
}
foreach ($v in ($byVersion.Keys | Sort-Object { [double]$_ })) {
    if ($byVersion[$v].Count -gt 1) {
        $problems.Add("版本 $v 被多个迁移占用: $($byVersion[$v] -join ' / ')")
    }
}

# ── B. 没有幽灵迁移 ───────────────────────────────────────────
foreach ($e in $sqlEntries) {
    $expected = Join-Path $sqlSrcDir $e.Name
    if (-not (Test-Path $expected)) {
        $problems.Add("幽灵 SQL 迁移（源码里已不存在）: $($e.Name)")
    }
}
foreach ($e in $javaEntries) {
    $javaName = [IO.Path]::GetFileNameWithoutExtension($e.Name) + '.java'
    $expected = Join-Path $javaSrcDir $javaName
    if (-not (Test-Path $expected)) {
        $problems.Add("幽灵 Java 迁移（源码里已不存在）: $javaName")
    }
}

# ── 结果 ──────────────────────────────────────────────────────
$maxVersion = ($byVersion.Keys | Sort-Object { [double]$_ } | Select-Object -Last 1)
Write-Host "版本号 : $($byVersion.Count) 个（最大 V$maxVersion）"

if ($problems.Count -gt 0) {
    Write-Host ""
    Write-Host "[FAIL] 这个 jar 的迁移链是脏的，启动时 Flyway 会拒绝（Found more than one migration / 幽灵迁移）:" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" }
    Write-Host ""
    Write-Host "修复：mvn clean 后重新打包（只 mvn package 不会清掉旧的 target/classes 资源）。"
    exit 1
}
Write-Host "[OK] 迁移链干净：版本唯一，且每个迁移都能在源码树里找到" -ForegroundColor Green
exit 0

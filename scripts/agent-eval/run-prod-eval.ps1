param(
    [string]$Tier = "all",
    [string]$Category = "all",
    [string]$RunId = "",
    [string]$Ids = "",
    [string]$CasesFile = "",
    [switch]$SmokeOnly,
    [switch]$AllowPaid,
    [string]$Adapter = "",
    [string]$ModelIdentity = "",
    [string]$OutputRoot = ""
)
$ErrorActionPreference = "Stop"
# Opt in before loading any helper, contacting a server or creating a run.
if (-not $AllowPaid) {
    Write-Error "Live evaluation requires -AllowPaid, isolated test -Adapter, -CasesFile and -ModelIdentity. Offline: node --test scripts/agent-eval/quality-gate.test.mjs." -ErrorAction Continue
    exit 2
}
if (-not $CasesFile -or -not $Adapter -or -not $ModelIdentity) {
    Write-Error "Missing -CasesFile/-Adapter/-ModelIdentity. Legacy substring-only cases are not production manifests. See scripts/agent-eval/README.md." -ErrorAction Continue
    exit 2
}
if ($SmokeOnly) { $Tier = "smoke" }
$evalArguments = @((Join-Path $PSScriptRoot 'run-eval.mjs'), '--live', '--allow-paid',
    '--manifest', $CasesFile, '--adapter', $Adapter, '--model-identity', $ModelIdentity,
    '--tier', $Tier, '--category', $Category)
if ($RunId) { $evalArguments += @('--run-id', $RunId) }
if ($Ids) { $evalArguments += @('--ids', $Ids) }
if ($OutputRoot) { $evalArguments += @('--output', $OutputRoot) }
& node @evalArguments
exit $LASTEXITCODE

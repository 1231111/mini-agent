param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$ReportRoot = "",
    [switch]$RequireLive,
    [string]$Commit = "",
    [string]$ManifestSha256 = "",
    [string]$AdapterSha256 = "",
    [string]$TargetSha256 = "",
    [string]$TrustedPublicKey = ""
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}$') { throw 'Invalid RunId' }
if (-not $ReportRoot) { $ReportRoot = Join-Path $PSScriptRoot '../../.verify/_agent-eval' }
$verifyArguments = @((Join-Path $PSScriptRoot 'verify-report.mjs'), (Join-Path $ReportRoot "$RunId/report.json"))
if ($RequireLive) { $verifyArguments += '--require-live' }
if ($Commit) { $verifyArguments += @('--commit', $Commit) }
if ($ManifestSha256) { $verifyArguments += @('--manifest-sha256', $ManifestSha256) }
if ($AdapterSha256) { $verifyArguments += @('--adapter-sha256', $AdapterSha256) }
if ($TargetSha256) { $verifyArguments += @('--target-sha256', $TargetSha256) }
if ($TrustedPublicKey) { $verifyArguments += @('--trusted-public-key', $TrustedPublicKey) }
& node @verifyArguments
exit $LASTEXITCODE

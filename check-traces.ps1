param([Parameter(Mandatory = $true)][string]$SessionId)
# Pull traces while the task is still running: proves ownership resolves during execution.

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\agent-api.ps1"
$api = Connect-Agent

$running = Invoke-RestMethod "$($api.Base)/api/task-status?sessionId=$SessionId" `
    -WebSession $api.Session
Write-Host "RUNNING=$($running.data.running)"

$traces = Invoke-RestMethod "$($api.Base)/api/traces?sessionId=$SessionId" -WebSession $api.Session
Write-Host "TRACE_COUNT=$($traces.data.Count)"
$traces.data | Select-Object -Last 15 |
    ForEach-Object { "  [{0}] {1} {2} {3}" -f $_.turnIndex, $_.stepType, $_.toolName, $_.status }

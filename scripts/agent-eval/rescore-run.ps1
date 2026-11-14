param([Parameter(Mandatory = $true)][string]$RunId, [string]$ReportRoot = "")
# Never dot-source the live runner or perform paid retries during report inspection.
Write-Error 'Legacy rescoring is disabled: truncated answers/shared old files cannot upgrade a failure to a pass. Use verify-run.ps1 for a read-only gate, or start a new isolated run with -AllowPaid.'
exit 2

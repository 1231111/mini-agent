$ErrorActionPreference = "Stop"

$internalKeyBytes = New-Object byte[] 32
$memoryKeyBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($internalKeyBytes)
    $rng.GetBytes($memoryKeyBytes)
} finally {
    $rng.Dispose()
}

Write-Output "# Put these values in the server .env file; keep them private."
Write-Output "ACCOUNT_INTERNAL_KEY=$([Convert]::ToBase64String($internalKeyBytes))"
Write-Output "ACCOUNT_MEMORY_TOKEN_KEY=$([Convert]::ToBase64String($memoryKeyBytes))"

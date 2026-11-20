# Dot-source in each new PowerShell window. Keep the generated file private.
$settingsPath = Join-Path $PSScriptRoot '../.env'
if (-not (Test-Path -LiteralPath $settingsPath)) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $dbBytes = New-Object byte[] 32
        $jwtBytes = New-Object byte[] 32
        $rng.GetBytes($dbBytes)
        $rng.GetBytes($jwtBytes)
        $settings = @(
            "DB_PASSWORD=$([Convert]::ToBase64String($dbBytes))"
            "JWT_SECRET=$([Convert]::ToBase64String($jwtBytes))"
        )
        [System.IO.File]::WriteAllLines($settingsPath, $settings)
    } finally {
        $rng.Dispose()
    }
}
foreach ($line in (Get-Content -LiteralPath $settingsPath)) {
    $pair = $line -split '=', 2
    if ($pair.Count -eq 2 -and $pair[0] -in @('DB_PASSWORD', 'JWT_SECRET')) {
        [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
    }
}

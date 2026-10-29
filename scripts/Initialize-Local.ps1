$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$environmentFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $randomBytes = New-Object byte[] 24
    $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $randomGenerator.GetBytes($randomBytes)
    $randomGenerator.Dispose()
    $localPassword = [Convert]::ToBase64String($randomBytes)
    $contents = "POSTGRES_DB=jibro`nPOSTGRES_USER=jibro`nPOSTGRES_PASSWORD=$localPassword`nPOSTGRES_PORT=15432`n"
    [System.IO.File]::WriteAllText($environmentFile, $contents, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Created the ignored local .env file with a random database password.'
}
if (-not (Select-String -LiteralPath $environmentFile -Pattern '^JWT_SECRET=' -Quiet)) {
    $signingBytes = New-Object byte[] 48
    $signingGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $signingGenerator.GetBytes($signingBytes)
    $signingGenerator.Dispose()
    $signingSecret = [Convert]::ToBase64String($signingBytes)
    [System.IO.File]::AppendAllText($environmentFile, "`nJWT_SECRET=$signingSecret`nAUTH_SECURE_COOKIE=false`nFRONTEND_URL=http://127.0.0.1:5173`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Added a random local JWT signing key without changing the database password.'
}
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
Push-Location $projectRoot
try {
    docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL startup failed. Check that Docker Desktop is running.' }
} finally {
    Pop-Location
}

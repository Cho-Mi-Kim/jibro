param([switch]$SampleData)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
if (-not $env:DB_PASSWORD) { throw 'Run scripts/Initialize-Local.ps1 first or set DB_PASSWORD.' }
if (-not $env:JWT_SECRET) { throw 'Run scripts/Initialize-Local.ps1 to add a local signing key, or set JWT_SECRET.' }
Push-Location (Join-Path $projectRoot 'backend')
try {
    if ($SampleData) { .\gradlew.bat bootRun '--args=--spring.profiles.active=local-sample' }
    else { .\gradlew.bat bootRun }
} finally {
    Pop-Location
}

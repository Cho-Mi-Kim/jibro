$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
if (-not $env:DB_PASSWORD) { throw 'Run scripts/Initialize-Local.ps1 first or set DB_PASSWORD.' }
Push-Location (Join-Path $projectRoot 'backend')
try {
    .\gradlew.bat bootRun
} finally {
    Pop-Location
}

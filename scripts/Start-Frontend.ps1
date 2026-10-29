$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
$nodeVersion = node --version
if ($nodeVersion -notmatch '^v24\.') { throw 'Node.js 24 LTS is required. See README.md.' }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
    }
    npm.cmd run dev
} finally {
    Pop-Location
}

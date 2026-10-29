$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localNode = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'node-v24*-win-x64' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localNode) {
    $env:PATH = $localNode.FullName + ';' + $env:PATH
}
$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk -and (Test-Path -LiteralPath (Join-Path $localJdk.FullName 'bin/java.exe'))) {
    $env:JAVA_HOME = $localJdk.FullName
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-cache'
$env:npm_config_cache = Join-Path $projectRoot '.tools/npm-cache'
$environmentFile = Join-Path $projectRoot '.env'
$projectVariables = @{}
if (Test-Path -LiteralPath $environmentFile) {
    foreach ($line in Get-Content -LiteralPath $environmentFile) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $projectVariables[$Matches[1]] = $Matches[2]
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}
if ($env:POSTGRES_PASSWORD) { $env:DB_PASSWORD = $env:POSTGRES_PASSWORD }
if ($env:POSTGRES_USER) { $env:DB_USERNAME = $env:POSTGRES_USER }
$databasePort = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '15432' }
$databaseName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'jibro' }
# Ignore another project's inherited generic DB_URL unless this project's .env explicitly sets it.
$env:DB_URL = if ($projectVariables.ContainsKey('DB_URL')) { $projectVariables['DB_URL'] } else { "jdbc:postgresql://localhost:$databasePort/$databaseName" }
$env:TEST_DB_URL = if ($projectVariables.ContainsKey('TEST_DB_URL')) { $projectVariables['TEST_DB_URL'] } else { "jdbc:postgresql://localhost:$databasePort/jibro_test" }

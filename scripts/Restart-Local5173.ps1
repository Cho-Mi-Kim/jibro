param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-DevEnvironment.ps1')
Set-Location $projectRoot
try {
    foreach ($port in @(5173, 8080)) {
        $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
        foreach ($listener in $listeners) {
            $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
            $expected = if ($port -eq 5173) { 'vite' } else { 'backend-service-scope.jar' }
            if ($serverProcess.CommandLine -notlike "*$expected*") {
                throw "Port $port belongs to another application. Restart stopped."
            }
            Stop-Process -Id $listener.OwningProcess -Force
        }
    }
    Start-Sleep -Seconds 1
    $pgCtl = Join-Path $projectRoot '.tools/pgsql/bin/pg_ctl.exe'
    $pgData = Join-Path $projectRoot '.tools/pgdata'
    & $pgCtl -D $pgData status *> $null
    if ($LASTEXITCODE -ne 0) {
        # A separate hidden process prevents terminal interruption from shutting down PostgreSQL.
        $pgLog = Join-Path $projectRoot '.tools/postgres.log'
        $pgArgs = @('-D', ('"' + $pgData + '"'), '-l', ('"' + $pgLog + '"'), '-w', 'start')
        Start-Process -FilePath $pgCtl -ArgumentList $pgArgs -WindowStyle Hidden | Out-Null
        $pgReady = Join-Path $projectRoot '.tools/pgsql/bin/pg_isready.exe'
        $pgPort = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '15432' }
        $started = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            & $pgReady -h '127.0.0.1' -p $pgPort *> $null
            if ($LASTEXITCODE -eq 0) { $started = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $started) { throw 'Database startup failed. Check .tools/postgres.log.' }
    }
    $java = (Get-Command java.exe).Source
    $node = (Get-Command node.exe).Source
    $jar = Join-Path $projectRoot '.tools/runtime/backend-service-scope.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Backend runtime file is missing.' }
    $backend = Start-Process -FilePath $java -ArgumentList '-jar', $jar, '--spring.profiles.active=local-sample' -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.tools/runtime/backend-restart.log') -RedirectStandardError (Join-Path $projectRoot '.tools/runtime/backend-restart.err.log') -PassThru
    $frontend = Start-Process -FilePath $node -ArgumentList 'node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5173','--strictPort' -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot '.tools/frontend-restart.log') -RedirectStandardError (Join-Path $projectRoot '.tools/frontend-restart.err.log') -PassThru
    @(
        [pscustomobject]@{Name='frontend';Id=$frontend.Id;Path=$node;StartTimeUtc=$frontend.StartTime.ToUniversalTime().ToString('o')}
        [pscustomobject]@{Name='backend';Id=$backend.Id;Path=$java;StartTimeUtc=$backend.StartTime.ToUniversalTime().ToString('o')}
    ) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot '.tools/preview-processes.json') -Encoding UTF8
    $ready = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:5173/api/v1/health' -TimeoutSec 2
            if ($health.status -eq 'UP' -and $health.database -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Startup timed out. Check .tools/runtime/backend-restart.log and .tools/frontend-restart.log.' }
    Write-Host 'Ready: http://127.0.0.1:5173/'
    if (-not $NoBrowser) { Start-Process 'http://127.0.0.1:5173/' }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}

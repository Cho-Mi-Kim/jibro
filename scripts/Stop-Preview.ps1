$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$processFile = Join-Path $projectRoot '.tools/preview-processes.json'
if (-not (Test-Path -LiteralPath $processFile)) {
    Write-Output 'No recorded background preview processes. Stop foreground servers with Ctrl+C.'
    exit 0
}
foreach ($entry in (Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json)) {
    $previewProcess = Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if ($previewProcess -and $previewProcess.Path -eq $entry.Path -and
            $previewProcess.StartTime.ToUniversalTime().ToString('o') -eq $entry.StartTimeUtc) {
        Stop-Process -Id $previewProcess.Id
        Write-Output "Stopped preview process $($entry.Id)."
    }
}
Write-Output 'The PostgreSQL container is unchanged. Use docker compose stop postgres if needed.'

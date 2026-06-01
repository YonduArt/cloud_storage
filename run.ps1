$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

function Test-AiWorker {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:8090/health" -TimeoutSec 2
        return $response.status -eq "UP"
    } catch {
        return $false
    }
}

if (-not (Test-Path ".\ai-worker\.venv\Scripts\python.exe")) {
    throw "Local AI environment is missing. Run prepare-offline.ps1 once while internet is available."
}

if (-not (Test-AiWorker)) {
    Start-Process powershell -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-File", "`"$PSScriptRoot\ai-worker\start-local-ai.ps1`""
    )

    Write-Host "Waiting for local AI worker on http://localhost:8090 ..."
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        if (Test-AiWorker) {
            break
        }
        Start-Sleep -Seconds 3
    }
}

if (-not (Test-AiWorker)) {
    throw "AI worker did not start on http://localhost:8090."
}

docker compose -f docker-compose.yml -f docker-compose.local-ai.yml up -d postgres backend frontend

Write-Host ""
Write-Host "Ready."
Write-Host "Frontend:  http://localhost:5173"
Write-Host "Backend:   http://localhost:8080"
Write-Host "AI health: http://localhost:8090/health"

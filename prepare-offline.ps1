$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force -Path ".\offline" | Out-Null

Write-Host "Building Docker images for offline run..."
docker compose -f docker-compose.yml -f docker-compose.local-ai.yml build backend frontend
docker pull postgres:16-alpine

Write-Host "Saving Docker images to offline\docker-images.tar..."
docker save `
    postgres:16-alpine `
    my_diplom-backend:latest `
    my_diplom-frontend:latest `
    -o ".\offline\docker-images.tar"

Write-Host "Preparing local AI environment and model cache..."
powershell -ExecutionPolicy Bypass -File ".\ai-worker\prepare-offline-ai.ps1"

Write-Host ""
Write-Host "Offline preparation is complete."
Write-Host "Use one command to run later, even without internet:"
Write-Host "powershell -ExecutionPolicy Bypass -File .\run.ps1"

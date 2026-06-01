$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

if (-not (Test-Path ".\offline\docker-images.tar")) {
    throw "offline\docker-images.tar not found. Run prepare-offline.ps1 first while internet is available."
}

docker load -i ".\offline\docker-images.tar"

Write-Host "Docker images loaded. You can run:"
Write-Host "powershell -ExecutionPolicy Bypass -File .\run.ps1"

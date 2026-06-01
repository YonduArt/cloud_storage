$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

docker compose -f docker-compose.yml -f docker-compose.local-ai.yml up --build -d postgres
docker compose -f docker-compose.yml -f docker-compose.local-ai.yml up --build -d --no-deps backend
docker compose -f docker-compose.yml -f docker-compose.local-ai.yml up --build -d --no-deps frontend

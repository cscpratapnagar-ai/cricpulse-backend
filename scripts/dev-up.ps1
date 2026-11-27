[CmdletBinding()]
param(
    [switch]$DatabaseOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

try {
    Write-Host "Checking Docker Desktop / Docker Engine..." -ForegroundColor Cyan
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not running. Start Docker Desktop and run this script again."
    }

    Write-Host "Starting the local PostgreSQL service..." -ForegroundColor Cyan
    docker compose up -d postgres
    if ($LASTEXITCODE -ne 0) {
        throw "Could not start PostgreSQL. Check 'docker compose logs postgres'."
    }

    $ready = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        docker compose exec -T postgres pg_isready -U cricket_app -d cricket *> $null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }

    if (-not $ready) {
        docker compose logs --tail 80 postgres
        throw "PostgreSQL did not become ready on localhost:5432 within 60 seconds."
    }

    Write-Host "PostgreSQL is healthy: localhost:5432/cricket" -ForegroundColor Green

    if (-not $DatabaseOnly) {
        Write-Host "Starting CricPulse API. Press Ctrl+C to stop the API." -ForegroundColor Cyan
        mvn -B spring-boot:run
        if ($LASTEXITCODE -ne 0) {
            throw "CricPulse API exited with an error. PostgreSQL is still running; inspect the API log above."
        }
    }
}
finally {
    Pop-Location
}

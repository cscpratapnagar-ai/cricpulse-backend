[CmdletBinding()]
param(
    [switch]$DatabaseOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$DockerArgs,
        [switch]$Quiet
    )

    # Native stderr must not become a terminating PowerShell error. We inspect
    # LASTEXITCODE ourselves and provide a useful, actionable message instead.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        if ($Quiet) {
            & docker @DockerArgs *> $null
        }
        else {
            & docker @DockerArgs
        }
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Test-DockerEngine {
    $exitCode = Invoke-Docker -DockerArgs @("info") -Quiet
    return ($exitCode -eq 0)
}

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI was not found. Install Docker Desktop, reopen PowerShell, and run this script again."
    }

    Write-Host "Checking Docker Desktop / Docker Engine..." -ForegroundColor Cyan
    if (-not (Test-DockerEngine)) {
        Write-Host "Docker Engine is not running. Trying to start Docker Desktop..." -ForegroundColor Yellow

        $dockerDesktopCandidates = @(
            (Join-Path $env:ProgramFiles "Docker\Docker\Docker Desktop.exe"),
            (Join-Path $env:LOCALAPPDATA "Programs\Docker\Docker\Docker Desktop.exe")
        )
        $dockerDesktopPath = $dockerDesktopCandidates |
            Where-Object { $_ -and (Test-Path $_) } |
            Select-Object -First 1

        if ($dockerDesktopPath) {
            try {
                Start-Process -FilePath $dockerDesktopPath -ErrorAction Stop
            }
            catch {
                Write-Warning "Could not launch Docker Desktop automatically: $($_.Exception.Message)"
            }
        }
        else {
            Write-Warning "Docker Desktop executable was not found in its standard install locations."
        }

        $engineReady = $false
        for ($attempt = 1; $attempt -le 45; $attempt++) {
            Start-Sleep -Seconds 2
            if (Test-DockerEngine) {
                $engineReady = $true
                break
            }
            if ($attempt % 10 -eq 0) {
                Write-Host "Waiting for Docker Engine... ($($attempt * 2)/90 seconds)" -ForegroundColor DarkGray
            }
        }

        if (-not $engineReady) {
            Write-Host ""
            Write-Host "Docker contexts currently configured:" -ForegroundColor Yellow
            Invoke-Docker -DockerArgs @("context", "ls")
            throw @"
Docker Engine is still unavailable after 90 seconds.
Open Docker Desktop manually and wait until it shows Engine running.
Then verify with: docker info
If Docker Desktop is already open, check that it is using Linux containers and that the selected context is correct.
"@
        }
    }

    Write-Host "Docker Engine is available." -ForegroundColor Green

    Write-Host "Starting the local PostgreSQL service..." -ForegroundColor Cyan
    $composeExitCode = Invoke-Docker -DockerArgs @("compose", "up", "-d", "postgres")
    if ($composeExitCode -ne 0) {
        throw "Could not start PostgreSQL. Check 'docker compose logs postgres'."
    }

    $ready = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $readyExitCode = Invoke-Docker -DockerArgs @("compose", "exec", "-T", "postgres", "pg_isready", "-U", "cricket_app", "-d", "cricket") -Quiet
        if ($readyExitCode -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }

    if (-not $ready) {
        Invoke-Docker -DockerArgs @("compose", "logs", "--tail", "80", "postgres")
        throw "PostgreSQL did not become ready on localhost:5432 within 60 seconds."
    }

    Write-Host "PostgreSQL is healthy: localhost:5432/cricket" -ForegroundColor Green

    if (-not $DatabaseOnly) {
        # Force the documented local container settings for this process only,
        # so inherited staging/managed-database variables cannot redirect local startup.
        $env:DATABASE_URL = "jdbc:postgresql://localhost:5432/cricket?sslmode=disable"
        $env:DATABASE_USERNAME = "cricket_app"
        $env:DATABASE_PASSWORD = "cricket_dev_password"
        $env:AUTH_COOKIE_SECURE = "false"

        Write-Host "Starting CricPulse API. Press Ctrl+C to stop the API." -ForegroundColor Cyan
        $ErrorActionPreference = "Continue"
        mvn -B spring-boot:run
        $mavenExitCode = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        if ($mavenExitCode -ne 0) {
            throw "CricPulse API exited with an error. PostgreSQL is still running; inspect the API log above."
        }
    }
}
finally {
    Pop-Location
}

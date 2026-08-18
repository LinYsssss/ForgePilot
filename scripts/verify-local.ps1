param(
    [int]$BackendPort = 18080,
    [switch]$SkipSmoke,
    [string]$SmokeAdminUser = "smoke-admin",
    [string]$SmokeAdminPassword = ""
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BackendDir = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$SmokeScript = Join-Path $PSScriptRoot "smoke-backend.ps1"
$DemoInitScript = Join-Path $PSScriptRoot "init-demo-repos.ps1"
if ([string]::IsNullOrWhiteSpace($SmokeAdminPassword)) {
    $SmokeAdminPassword = "Smoke-" + [Guid]::NewGuid().ToString("N")
}
$Results = [ordered]@{}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Name"
    $start = Get-Date
    try {
        & $Action
        $Results[$Name] = "PASS ($([int]((Get-Date) - $start).TotalSeconds)s)"
        Write-Host "PASS: $Name"
    }
    catch {
        $Results[$Name] = "FAIL: $($_.Exception.Message)"
        throw
    }
}

function Invoke-CommandChecked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath exited with code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Wait-Backend {
    param([string]$BaseUrl)

    $deadline = (Get-Date).AddSeconds(90)
    do {
        try {
            $health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") {
                return
            }
        }
        catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "backend did not become healthy at $BaseUrl"
}

function Invoke-Smoke {
    if (-not (Test-Path -LiteralPath $DemoInitScript)) {
        throw "missing demo repository initializer: $DemoInitScript"
    }
    Invoke-CommandChecked -FilePath "pwsh" -Arguments @("-NoProfile", "-File", $DemoInitScript, "-Verify") -WorkingDirectory $Root

    $baseUrl = "http://localhost:$BackendPort"
    $job = Start-Job -ScriptBlock {
        param($dir, $port, $seedUser, $seedPassword)
        Set-Location $dir
        $env:APP_ENV = "dev"
        $env:SERVER_PORT = "$port"
        $env:SEED_ADMIN_USERNAME = $seedUser
        $env:SEED_ADMIN_PASSWORD = $seedPassword
        $env:SEED_ADMIN_ROLE = "ADMIN"
        $env:AI_PROVIDER = "mock"
        $env:EMBEDDING_PROVIDER = "mock"
        $env:REVIEW_INLINE = "true"
        $env:GIT_ALLOW_LOCAL_PATH = "true"
        mvn -s .mvn\settings.xml spring-boot:run
    } -ArgumentList $BackendDir, $BackendPort, $SmokeAdminUser, $SmokeAdminPassword

    try {
        Wait-Backend -BaseUrl $baseUrl
        & $SmokeScript -BaseUrl $baseUrl -AdminUser $SmokeAdminUser -AdminPassword $SmokeAdminPassword
        if ($LASTEXITCODE -ne 0) {
            throw "smoke script exited with code $LASTEXITCODE"
        }
    }
    finally {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

function Test-DockerAvailability {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "SKIP: docker command not found"
        $Results["Docker availability"] = "SKIP: docker command not found"
        return
    }
    docker --version
    if ($LASTEXITCODE -ne 0) {
        throw "docker --version failed"
    }
    $Results["Docker availability"] = "PASS"
}

try {
    Invoke-Step "Backend tests" {
        Invoke-CommandChecked -FilePath "mvn" -Arguments @("-s", ".mvn\settings.xml", "test") -WorkingDirectory $BackendDir
    }

    Invoke-Step "Frontend tests" {
        Invoke-CommandChecked -FilePath "npm" -Arguments @("test") -WorkingDirectory $FrontendDir
    }

    Invoke-Step "Frontend build" {
        Invoke-CommandChecked -FilePath "npm" -Arguments @("run", "build") -WorkingDirectory $FrontendDir
    }

    if (-not $SkipSmoke) {
        Invoke-Step "Backend smoke" {
            Invoke-Smoke
        }
    }
    else {
        $Results["Backend smoke"] = "SKIP: requested"
    }

    Write-Host ""
    Write-Host "==> Docker availability"
    Test-DockerAvailability

    Write-Host ""
    Write-Host "ForgePilot local verification summary:"
    foreach ($item in $Results.GetEnumerator()) {
        Write-Host ("- {0}: {1}" -f $item.Key, $item.Value)
    }
}
catch {
    Write-Host ""
    Write-Host "ForgePilot local verification failed:"
    foreach ($item in $Results.GetEnumerator()) {
        Write-Host ("- {0}: {1}" -f $item.Key, $item.Value)
    }
    throw
}

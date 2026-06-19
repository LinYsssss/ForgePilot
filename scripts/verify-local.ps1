param(
    [int]$BackendPort = 18080,
    [switch]$SkipSmoke
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BackendDir = Join-Path $Root "backend"
$FrontendDir = Join-Path $Root "frontend"
$ModelDir = Join-Path $Root "model-service"
$SmokeScript = Join-Path $PSScriptRoot "smoke-backend.ps1"
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

function Invoke-ModelCheck {
    Push-Location $ModelDir
    $previousPythonPath = $env:PYTHONPATH
    try {
        $localPackages = Join-Path $ModelDir ".python-packages"
        $requirements = Join-Path $ModelDir "requirements.txt"
        $dependencyCheck = @'
import fastapi
import joblib
import sklearn
'@

        $env:PYTHONPATH = $localPackages
        $dependencyCheck | python -
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Model dependencies missing; installing into $localPackages"
            python -m pip install --disable-pip-version-check --target $localPackages -r $requirements
            if ($LASTEXITCODE -ne 0) {
                throw "model dependency installation failed with code $LASTEXITCODE"
            }
        }

        $code = @'
from app.main import PredictRequest, load_model, model_status, predict
load_model()
status = model_status().model_dump()
result = predict(PredictRequest(
    filePath="AdminOrderController.java",
    diffText="+@PostMapping(\"/admin/orders/{id}/force-ship\")\n+public void forceShip(Long id){ orderService.forceShip(id); }"
)).model_dump()
print(status)
print(result)
if not status["modelLoaded"]:
    raise SystemExit("model not loaded")
if result["source"] != "trained-model":
    raise SystemExit("trained model not used")
'@
        $code | python -
        if ($LASTEXITCODE -ne 0) {
            throw "model check failed with code $LASTEXITCODE"
        }
    }
    finally {
        $env:PYTHONPATH = $previousPythonPath
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
    $baseUrl = "http://localhost:$BackendPort"
    $job = Start-Job -ScriptBlock {
        param($dir, $port)
        Set-Location $dir
        $env:SERVER_PORT = "$port"
        mvn -s .mvn\settings.xml spring-boot:run
    } -ArgumentList $BackendDir, $BackendPort

    try {
        Wait-Backend -BaseUrl $baseUrl
        & $SmokeScript -BaseUrl $baseUrl
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

    Invoke-Step "Model service check" {
        Invoke-ModelCheck
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
    Write-Host "RepoSage local verification summary:"
    foreach ($item in $Results.GetEnumerator()) {
        Write-Host ("- {0}: {1}" -f $item.Key, $item.Value)
    }
}
catch {
    Write-Host ""
    Write-Host "RepoSage local verification failed:"
    foreach ($item in $Results.GetEnumerator()) {
        Write-Host ("- {0}: {1}" -f $item.Key, $item.Value)
    }
    throw
}

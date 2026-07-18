param(
  [switch]$SkipStatic,
  [switch]$KeepCompose,
  [string]$ComposeFile = (Join-Path $PSScriptRoot '..\deploy\docker-compose.yml')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Invoke-Gate([string]$Name, [scriptblock]$Action) {
  Write-Host "`n== $Name ==" -ForegroundColor Cyan
  $global:LASTEXITCODE = 0
  & $Action
  if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

if (-not $SkipStatic) {
  Invoke-Gate 'backend tests' { Push-Location (Join-Path $root 'backend'); try { mvn -q test } finally { Pop-Location } }
  Invoke-Gate 'frontend tests' { Push-Location (Join-Path $root 'frontend'); try { npm test } finally { Pop-Location } }
  Invoke-Gate 'frontend production build' { Push-Location (Join-Path $root 'frontend'); try { npm run build } finally { Pop-Location } }
  Invoke-Gate 'sandbox-runner tests' { Push-Location (Join-Path $root 'sandbox-runner'); try { mvn -q test } finally { Pop-Location } }
  Invoke-Gate 'evaluation comparison input' { & (Join-Path $root 'scripts\run-agent-evaluation.ps1') -Split development -Runtime compare -Output (Join-Path $env:TEMP 'reposage-agent-evaluation-input.json') }
  Invoke-Gate 'git diff check' { Push-Location $root; try { git diff --check } finally { Pop-Location } }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Host 'DYNAMIC_ACCEPTANCE_INCOMPLETE: docker command is not installed. Compose, Testcontainers, RabbitMQ->Runner, container security, and real SCM E2E were not executed.' -ForegroundColor Red
  exit 2
}

Invoke-Gate 'docker version' { docker version }
Invoke-Gate 'docker compose version' { docker compose version }
Invoke-Gate 'docker compose config' { docker compose -f $ComposeFile config --quiet }
Invoke-Gate 'docker compose build' { docker compose -f $ComposeFile build }
Invoke-Gate 'docker compose up' { docker compose -f $ComposeFile up -d --wait }
Invoke-Gate 'docker compose ps' { docker compose -f $ComposeFile ps }

try {
  Invoke-Gate 'backend tests with Testcontainers' { Push-Location (Join-Path $root 'backend'); try { mvn -q test } finally { Pop-Location } }
} finally {
  if (-not $KeepCompose) { docker compose -f $ComposeFile down -v }
}

Write-Host "`nLANGCHAIN4J_AGENT_RELEASE_ACCEPTANCE_PASSED" -ForegroundColor Green

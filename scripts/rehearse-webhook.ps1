[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PayloadPath = (Join-Path $PSScriptRoot "..\backend\src\test\resources\webhooks\github\opened.json"),
    [Parameter(Mandatory = $true)]
    [string]$WebhookSecret,
    [string]$DeliveryId = ("p8-offline-{0}" -f (Get-Date -Format "yyyyMMddHHmmss")),
    [string]$AdminUser = "",
    [string]$AdminPassword = "",
    [int]$TimeoutSeconds = 120,
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
if ([string]::IsNullOrWhiteSpace($WebhookSecret)) {
    throw "WebhookSecret is required and must be supplied outside the repository."
}
$payloadFullPath = (Resolve-Path -LiteralPath $PayloadPath).Path
$payloadBytes = [System.IO.File]::ReadAllBytes($payloadFullPath)

function Get-HmacSha256Hex {
    param([byte[]]$Body, [string]$Secret)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    try {
        return (($hmac.ComputeHash($Body) | ForEach-Object { $_.ToString("x2") }) -join "")
    }
    finally {
        $hmac.Dispose()
    }
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path
    )
    return Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -WebSession $session
}

function Assert-ApiOk {
    param([object]$Response, [string]$Step)
    if ($null -eq $Response) {
        throw "$Step returned an empty response"
    }
    if (($Response.PSObject.Properties.Name -contains "code") -and $Response.code -ne 0) {
        throw "$Step failed: code=$($Response.code), message=$($Response.message)"
    }
}

$bodySha = [System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::HashData($payloadBytes)).Replace("-", "").ToLowerInvariant()
$signature = "sha256=$(Get-HmacSha256Hex -Body $payloadBytes -Secret $WebhookSecret)"
$headers = @{
    "X-GitHub-Event" = "pull_request"
    "X-GitHub-Delivery" = $DeliveryId
    "X-Hub-Signature-256" = $signature
}

$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health"
if ($health.status -ne "UP") {
    throw "Backend health is not UP: $($health.status)"
}

$response = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/webhooks/scm/github" `
    -Headers $headers -ContentType "application/json" -Body $payloadBytes
$ack = $response.Content | ConvertFrom-Json
Assert-ApiOk $ack "webhook injection"
$agentRunId = $ack.data.agentRunId
$run = $null
$timeline = $null
if (-not [string]::IsNullOrWhiteSpace($AdminUser) -and -not [string]::IsNullOrWhiteSpace($AdminPassword) -and $null -ne $agentRunId) {
    $null = Invoke-WebRequest -Method Get -Uri "$BaseUrl/api/auth/csrf" -WebSession $session
    $csrfCookie = $session.Cookies.GetCookies($BaseUrl) |
        Where-Object Name -eq "XSRF-TOKEN" | Select-Object -First 1
    if ($null -eq $csrfCookie) {
        throw "CSRF bootstrap did not return an XSRF-TOKEN cookie"
    }
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" `
        -WebSession $session -Headers @{ "X-XSRF-TOKEN" = $csrfCookie.Value } `
        -ContentType "application/json; charset=utf-8" `
        -Body (@{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json)
    Assert-ApiOk $login "admin login"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $runResponse = Invoke-JsonApi -Method Get -Path "/api/agent-runs/$agentRunId"
        Assert-ApiOk $runResponse "agent run detail"
        $run = $runResponse.data
        if ($run.terminal) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $run -or -not $run.terminal) {
        throw "Agent run did not reach a terminal state within ${TimeoutSeconds}s: $agentRunId"
    }
    $timelineResponse = Invoke-JsonApi -Method Get -Path "/api/agent-runs/$agentRunId/timeline"
    Assert-ApiOk $timelineResponse "agent run timeline"
    $timeline = $timelineResponse.data
}

$record = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl = $BaseUrl
    payload = [System.IO.Path]::GetFileName($payloadFullPath)
    deliveryId = $DeliveryId
    payloadSha256 = $bodySha
    signatureAlgorithm = "HMAC-SHA256"
    signatureVerifiedBy = "/api/webhooks/scm/github"
    httpStatus = [int]$response.StatusCode
    outcome = $ack.data.outcome
    agentRunId = $agentRunId
    runStatus = if ($null -ne $run) { $run.status } else { $null }
    terminal = if ($null -ne $run) { $run.terminal } else { $null }
    gateVerdict = if ($null -ne $run) { $run.gateVerdict } else { $null }
    steps = if ($null -ne $timeline) { @($timeline.steps | ForEach-Object { @{ sequenceNo = $_.sequenceNo; stepType = $_.stepType; status = $_.status } }) } else { @() }
    secretStored = $true
    secretValueRecorded = $false
}

if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
    $evidenceFullPath = [System.IO.Path]::GetFullPath($EvidencePath)
    $parent = Split-Path -Parent $evidenceFullPath
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $recordJson = $record | ConvertTo-Json -Depth 12
    $markdown = @(
        "# Offline Webhook Rehearsal",
        "",
        "- Generated: $($record.generatedAt)",
        "- Payload: $($record.payload)",
        "- Delivery: $($record.deliveryId)",
        "- Payload SHA-256: $($record.payloadSha256)",
        "- Signature: HMAC-SHA256 over the exact payload bytes",
        "- HTTP status: $($record.httpStatus)",
        "- Outcome: $($record.outcome)",
        "- Agent run: $($record.agentRunId)",
        "- Terminal: $($record.terminal)",
        "- Run status: $($record.runStatus)",
        "- Gate verdict: $($record.gateVerdict)",
        "",
        "## Redaction",
        "",
        "The webhook secret was supplied out-of-band and was not written to this record.",
        "",
        "## Machine-readable record",
        "",
        '```json',
        $recordJson,
        '```'
    )
    [System.IO.File]::WriteAllLines($evidenceFullPath, $markdown, (New-Object System.Text.UTF8Encoding($false)))
}

[pscustomobject]$record | ConvertTo-Json -Depth 12

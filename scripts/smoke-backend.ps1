param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$RepoPath = "",
    [int]$TimeoutSeconds = 60,
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin123"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

if ([string]::IsNullOrWhiteSpace($RepoPath)) {
    $RepoPath = (Resolve-Path (Join-Path $PSScriptRoot "..\demo-repos\mall-order-service")).Path
}

function Get-CsrfHeaders {
    $cookie = $Session.Cookies.GetCookies($BaseUrl) |
        Where-Object Name -eq "XSRF-TOKEN" | Select-Object -First 1
    if ($null -eq $cookie -or [string]::IsNullOrWhiteSpace($cookie.Value)) {
        throw "CSRF cookie is missing; call /api/auth/csrf before a write request"
    }
    return @{ "X-XSRF-TOKEN" = $cookie.Value }
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $Session
        Headers = (Get-CsrfHeaders)
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = $Body | ConvertTo-Json -Depth 20
    }
    return Invoke-RestMethod @params
}

function Invoke-UploadFile {
    param(
        [string]$Path,
        [string]$FilePath
    )

    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $true
    $handler.CookieContainer = $Session.Cookies
    $client = [System.Net.Http.HttpClient]::new($handler)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $request = $null
    try {
        $bytes = [System.IO.File]::ReadAllBytes($FilePath)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown")
        $multipart.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))

        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post, "$BaseUrl$Path")
        $request.Headers.Add("X-XSRF-TOKEN", (Get-CsrfHeaders)["X-XSRF-TOKEN"])
        $request.Content = $multipart
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Upload failed: HTTP $([int]$response.StatusCode) $body"
        }
        return $body | ConvertFrom-Json
    }
    finally {
        if ($null -ne $request) { $request.Dispose() }
        $multipart.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Assert-ApiOk {
    param(
        [object]$Response,
        [string]$Step
    )

    if ($null -eq $Response) {
        throw "$Step failed: empty response"
    }
    if ($Response.PSObject.Properties.Name -contains "code" -and $Response.code -ne 0) {
        throw "$Step failed: code=$($Response.code), message=$($Response.message)"
    }
}

$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health"
if ($health.status -ne "UP") {
    throw "Health check failed: $($health | ConvertTo-Json -Depth 5)"
}

# AuthController rotates the CSRF token and writes the auth token only to an HttpOnly cookie.
$csrfBootstrap = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/auth/csrf" -WebSession $Session
$login = Invoke-JsonApi -Method Post -Path "/api/auth/login" -Body @{
    username = $AdminUser
    password = $AdminPassword
}
Assert-ApiOk $login "login"
$username = $login.data.username

$suffix = Get-Date -Format "yyyyMMddHHmmss"
$project = Invoke-JsonApi -Method Post -Path "/api/projects" -Body @{
    name = "mall-order-review-$suffix"
    description = "API smoke test project"
    defaultBranch = "main"
}
Assert-ApiOk $project "create project"
$projectId = $project.data.projectId

$repo = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/repository" -Body @{
    repoUrl = $RepoPath
    provider = "LOCAL"
    defaultBranch = "main"
    accessToken = ""
}
Assert-ApiOk $repo "bind repository"

$commits = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/repository/commits?limit=5"
Assert-ApiOk $commits "list commits"
if ($commits.data.Count -lt 1) {
    throw "No commits found in demo repository"
}
$commit = $commits.data[0]

$securityDoc = Join-Path $RepoPath "docs\security-policy.md"
$upload = Invoke-UploadFile -Path "/api/projects/$projectId/knowledge/documents?docType=SECURITY" -FilePath $securityDoc
Assert-ApiOk $upload "upload knowledge"

$knowledgeSearch = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/knowledge/search" -Body @{
    query = "admin endpoint authorization and paid order shipping rule"
    topK = 5
}
Assert-ApiOk $knowledgeSearch "search knowledge"

$task = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/reviews/tasks" -Body @{
    commitId = $commit.commitId
    baseCommitId = $commit.parentCommitId
    branch = "main"
}
Assert-ApiOk $task "create review task"
$taskId = $task.data.taskId

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $taskDetail = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/tasks/$taskId"
    Assert-ApiOk $taskDetail "task detail"
    $status = $taskDetail.data.status
    if ($status -in @("SUCCESS", "FAILED", "DEAD", "CANCELED")) {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($status -ne "SUCCESS") {
    throw "Review task did not succeed. taskId=$taskId, status=$status"
}

$reports = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/reports?page=0&size=100"
Assert-ApiOk $reports "list reports"
$reportItems = @($reports.data.items)
if ($reportItems.Count -lt 1) {
    throw "No report generated"
}
$reportId = $reportItems[0].reportId

$report = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/reports/$reportId"
Assert-ApiOk $report "report detail"
if ($report.data.issues.Count -lt 1) {
    throw "No issues generated"
}
$issue = $report.data.issues[0]

$feedback = Invoke-JsonApi -Method Post -Path "/api/review-issues/$($issue.issueId)/feedback" -Body @{
    feedbackType = "TRUE_POSITIVE"
    comment = "Smoke test confirmed."
}
Assert-ApiOk $feedback "create feedback"

$mqLogs = Invoke-JsonApi -Method Get -Path "/api/mq/logs?taskId=$taskId&page=0&size=100"
Assert-ApiOk $mqLogs "mq logs"

$aiLogs = Invoke-JsonApi -Method Get -Path "/api/ai/logs?projectId=$($projectId)&page=0&size=100"
Assert-ApiOk $aiLogs "ai logs"
$mqItems = @($mqLogs.data.items)
$aiItems = @($aiLogs.data.items)

[PSCustomObject]@{
    Product = "ForgePilot"
    Health = $health.status
    Username = $username
    ProjectId = $projectId
    RepositoryStatus = $repo.data.status
    KnowledgeStatus = $upload.data.status
    SearchMatches = $knowledgeSearch.data.matches.Count
    TaskId = $taskId
    TaskStatus = $status
    OverallRisk = $report.data.overallRisk
    FirstIssue = $issue.category
    FeedbackId = $feedback.data.feedbackId
    MqLogCount = $mqItems.Count
    AiLogCount = $aiItems.Count
    AiLogTypes = (($aiItems | ForEach-Object { $_.requestType } | Sort-Object -Unique) -join ",")
    AiTotalTokens = (($aiItems | Measure-Object -Property totalTokens -Sum).Sum)
} | Format-List

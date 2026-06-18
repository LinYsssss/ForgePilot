param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$RepoPath = "",
    [int]$TimeoutSeconds = 60,
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin123"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoPath)) {
    $RepoPath = (Resolve-Path (Join-Path $PSScriptRoot "..\demo-repos\mall-order-service")).Path
}

function Invoke-JsonApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = ""
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    }

    $json = $Body | ConvertTo-Json -Depth 20
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json; charset=utf-8" -Body $json
}

function Invoke-UploadFile {
    param(
        [string]$Path,
        [string]$FilePath,
        [string]$Token
    )

    Add-Type -AssemblyName System.Net.Http

    $client = [System.Net.Http.HttpClient]::new()
    $content = $null
    try {
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
        $content = [System.Net.Http.MultipartFormDataContent]::new()
        $bytes = [System.IO.File]::ReadAllBytes($FilePath)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/markdown")
        $content.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))

        $response = $client.PostAsync("$BaseUrl$Path", $content).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Upload failed: HTTP $([int]$response.StatusCode) $body"
        }
        return $body | ConvertFrom-Json
    }
    finally {
        if ($null -ne $content) {
            $content.Dispose()
        }
        $client.Dispose()
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

$suffix = Get-Date -Format "yyyyMMddHHmmss"

# Registration is no longer a public endpoint; the first user is provisioned via the
# SEED_ADMIN_* env vars (AuthSeedRunner). Authenticate as that seeded admin.
$login = Invoke-JsonApi -Method Post -Path "/api/auth/login" -Body @{
    username = $AdminUser
    password = $AdminPassword
}
Assert-ApiOk $login "login"
$token = $login.data.token
$username = $AdminUser

$project = Invoke-JsonApi -Method Post -Path "/api/projects" -Token $token -Body @{
    name = "mall-order-review-$suffix"
    description = "API smoke test project"
    defaultBranch = "main"
}
Assert-ApiOk $project "create project"
$projectId = $project.data.projectId

$repo = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/repository" -Token $token -Body @{
    repoUrl = $RepoPath
    provider = "LOCAL"
    defaultBranch = "main"
    accessToken = ""
}
Assert-ApiOk $repo "bind repository"

$commits = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/repository/commits?limit=5" -Token $token
Assert-ApiOk $commits "list commits"
if ($commits.data.Count -lt 1) {
    throw "No commits found in demo repository"
}
$commit = $commits.data[0]

$securityDoc = Join-Path $RepoPath "docs\security-policy.md"
$upload = Invoke-UploadFile -Path "/api/projects/$projectId/knowledge/documents?docType=SECURITY" -FilePath $securityDoc -Token $token
Assert-ApiOk $upload "upload knowledge"

$knowledgeSearch = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/knowledge/search" -Token $token -Body @{
    query = "admin endpoint authorization and paid order shipping rule"
    topK = 5
}
Assert-ApiOk $knowledgeSearch "search knowledge"

$task = Invoke-JsonApi -Method Post -Path "/api/projects/$projectId/reviews/tasks" -Token $token -Body @{
    commitId = $commit.commitId
    baseCommitId = $commit.parentCommitId
    branch = "main"
}
Assert-ApiOk $task "create review task"
$taskId = $task.data.taskId

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
do {
    $taskDetail = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/tasks/$taskId" -Token $token
    Assert-ApiOk $taskDetail "task detail"
    $status = $taskDetail.data.status
    if ($status -in @("SUCCESS", "FAILED", "DEAD")) {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($status -ne "SUCCESS") {
    throw "Review task did not succeed. taskId=$taskId, status=$status"
}

$reports = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/reports" -Token $token
Assert-ApiOk $reports "list reports"
if ($reports.data.Count -lt 1) {
    throw "No report generated"
}
$reportId = $reports.data[0].reportId

$report = Invoke-JsonApi -Method Get -Path "/api/projects/$projectId/reviews/reports/$reportId" -Token $token
Assert-ApiOk $report "report detail"
if ($report.data.issues.Count -lt 1) {
    throw "No issues generated"
}
$issue = $report.data.issues[0]

$feedback = Invoke-JsonApi -Method Post -Path "/api/review-issues/$($issue.issueId)/feedback" -Token $token -Body @{
    feedbackType = "TRUE_POSITIVE"
    comment = "Smoke test confirmed."
}
Assert-ApiOk $feedback "create feedback"

$mqLogs = Invoke-JsonApi -Method Get -Path "/api/mq/logs?taskId=$taskId" -Token $token
Assert-ApiOk $mqLogs "mq logs"

$aiLogs = Invoke-JsonApi -Method Get -Path "/api/ai/logs?projectId=$($projectId)&limit=50" -Token $token
Assert-ApiOk $aiLogs "ai logs"

[PSCustomObject]@{
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
    MqLogCount = $mqLogs.data.Count
    AiLogCount = $aiLogs.data.Count
    AiLogTypes = (($aiLogs.data | ForEach-Object { $_.requestType } | Sort-Object -Unique) -join ",")
    AiTotalTokens = (($aiLogs.data | Measure-Object -Property totalTokens -Sum).Sum)
} | Format-List

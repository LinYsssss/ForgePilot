#Requires -Version 7
param([switch]$Verify)
$ErrorActionPreference = 'Stop'

$RootDir = Split-Path -Parent $PSScriptRoot
$Demo = Join-Path $RootDir 'demo-repos'
$Patches = Join-Path $Demo 'patches'
$Expected = Join-Path $RootDir 'scripts/demo-repos-expected-sha.txt'

$env:GIT_AUTHOR_NAME = 'RepoSage Demo'
$env:GIT_AUTHOR_EMAIL = 'demo@reposage.local'
$env:GIT_COMMITTER_NAME = 'RepoSage Demo'
$env:GIT_COMMITTER_EMAIL = 'demo@reposage.local'

$Repos = @(
  @{ Name = 'mall-order-service'; Branch = 'feature/promotion-batch-ship'; Patch = 'feature-promotion-batch-ship.patch' },
  @{ Name = 'payment-settlement-service'; Branch = 'feature/instant-settlement'; Patch = 'feature-instant-settlement.patch' },
  @{ Name = 'tenant-user-center'; Branch = 'feature/ops-console'; Patch = 'feature-ops-console.patch' }
)

function Invoke-CommitAt($Dir, $Stamp, $Message) {
  $env:GIT_AUTHOR_DATE = $Stamp
  $env:GIT_COMMITTER_DATE = $Stamp
  git -C $Dir commit -q --no-gpg-sign -m $Message
  Remove-Item Env:GIT_AUTHOR_DATE, Env:GIT_COMMITTER_DATE
}

foreach ($repo in $Repos) {
  $dir = Join-Path $Demo $repo.Name
  $patchFile = Join-Path $Patches (Join-Path $repo.Name $repo.Patch)
  if (-not (Test-Path $dir)) { throw "missing demo repo: $dir" }
  if (-not (Test-Path $patchFile)) { throw "missing patch: $patchFile" }
  if (Test-Path (Join-Path $dir '.git')) {
    Write-Host "already initialized, skipping: $($repo.Name)"
    continue
  }

  git -C $dir init -q -b main
  git -C $dir config core.autocrlf false
  git -C $dir config core.eol lf
  git -C $dir config commit.gpgsign false

  git -C $dir add -A ':!docs' ':!README.md'
  Invoke-CommitAt $dir '2026-01-15T10:00:00+08:00' 'feat: initial service implementation'

  git -C $dir add -A
  Invoke-CommitAt $dir '2026-01-15T11:00:00+08:00' 'docs: add knowledge base for review context'

  git -C $dir switch -q -c $repo.Branch
  git -C $dir apply $patchFile
  git -C $dir add -A
  Invoke-CommitAt $dir '2026-01-15T14:00:00+08:00' 'feat: implement the new feature for review'
  git -C $dir switch -q main

  Write-Host "initialized: $($repo.Name)"
}

if ($Verify) {
  if (-not (Test-Path $Expected)) { throw "expected sha list not found: $Expected" }
  $status = 0
  foreach ($line in Get-Content $Expected) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split '\s+'
    $actual = git -C (Join-Path $Demo $parts[0]) rev-parse $parts[1]
    if ($actual -eq $parts[2]) {
      Write-Host "ok  : $($parts[0]) $($parts[1])"
    } else {
      Write-Error "FAIL: $($parts[0]) $($parts[1]) expected $($parts[2]) but got $actual"
      $status = 1
    }
  }
  exit $status
}

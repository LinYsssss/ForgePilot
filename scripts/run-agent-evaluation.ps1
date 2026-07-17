param(
  [string]$Manifest = (Join-Path $PSScriptRoot '..\evaluation\manifest.json'),
  [ValidateSet('development','holdout','all')][string]$Split = 'development',
  [ValidateSet('legacy','langchain4j','compare')][string]$Runtime = 'compare',
  [string]$ObservedResults,
  [string]$Output = (Join-Path $PSScriptRoot '..\evaluation\results\latest-input.json')
)
$ErrorActionPreference = 'Stop'
$manifestPath = (Resolve-Path $Manifest).Path
$root = Split-Path $manifestPath
$data = Get-Content -Raw $manifestPath | ConvertFrom-Json
if ($data.fixedRun.temperature -ne 0) { throw 'Evaluation temperature must remain zero' }
if ($data.fixedRun.toolImage -notmatch '@sha256:[a-fA-F0-9]+$') { throw 'Tool image must be digest pinned' }
$cases = @($data.cases | Where-Object { $Split -eq 'all' -or $_.split -eq $Split })
foreach ($case in $cases) {
  $fixture = [IO.Path]::GetFullPath((Join-Path $root $case.fixture))
  if (-not $fixture.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path $fixture)) { throw "Missing fixture: $($case.id)" }
  if ($null -eq $case.expectedFindings -or $null -eq $case.nonFindings) { throw "Incomplete labels: $($case.id)" }
}
if ($Split -eq 'development') {
  $cases = @($cases | ForEach-Object { [pscustomobject]@{ id=$_.id; split=$_.split; language=$_.language; fixture=$_.fixture } })
}
$runtimes = if ($Runtime -eq 'compare') { @('legacy','langchain4j') } else { @($Runtime) }
$result = [ordered]@{ corpusVersion=$data.corpusVersion; schemaVersion=$data.schemaVersion; runtimeMetadata=$data.runtimeMetadata; runtimes=$runtimes; split=$Split; fixedRun=$data.fixedRun; cases=$cases; observedResults=$ObservedResults; safetyGates=[ordered]@{ fabricatedCitationRate=0; crossProjectRetrievalRate=0; unauthorizedToolExecutionRate=0; unapprovedPatchPublicationRate=0 } }
$outputPath = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
$result | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 $outputPath
Write-Output "Evaluation input written: $outputPath ($($cases.Count) cases)"

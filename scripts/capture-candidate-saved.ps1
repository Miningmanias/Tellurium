param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
    [string]$ReferenceRoot = '',
    [string]$OutputRoot = '',
    [string]$RunRoot = '',
    [string]$Seed = '0',
    [string]$Dimension = 'minecraft:overworld',
    [int]$ChunkX = 0,
    [int]$ChunkZ = 0
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($ReferenceRoot)) {
    throw 'Candidate saved verification requires -ReferenceRoot pointing at an independent original SAVED capture root.'
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('build\candidate-saved-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\run\candidate-saved-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$referenceRootAbsolute = [IO.Path]::GetFullPath($ReferenceRoot)
$outputRootAbsolute = [IO.Path]::GetFullPath($OutputRoot)
$runRootAbsolute = [IO.Path]::GetFullPath($RunRoot)
if (-not (Test-Path -LiteralPath (Join-Path $referenceRootAbsolute 'expected.snap'))) {
    throw "Independent original SAVED capture is missing: $(Join-Path $referenceRootAbsolute 'expected.snap')"
}
function Test-SameOrChild([string]$path, [string]$parent) {
    $normalizedPath = $path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
$roots = @($referenceRootAbsolute, $outputRootAbsolute, $runRootAbsolute)
for ($i = 0; $i -lt $roots.Count; $i++) {
    for ($j = $i + 1; $j -lt $roots.Count; $j++) {
        if (Test-SameOrChild $roots[$i] $roots[$j] -or Test-SameOrChild $roots[$j] $roots[$i]) {
            throw 'Reference, candidate output and candidate run roots must be distinct.'
        }
    }
}
New-Item -ItemType Directory -Force -Path $outputRootAbsolute, $runRootAbsolute | Out-Null

$expected = Join-Path $outputRootAbsolute 'expected.snap'
$reopened = Join-Path $outputRootAbsolute 'reopened.snap'
$reference = Join-Path $referenceRootAbsolute 'expected.snap'
$runDir = Join-Path $runRootAbsolute 'saved-world'
$reportPath = Join-Path $outputRootAbsolute 'saved-reopen-report.json'
if ((Test-Path -LiteralPath $expected) -or (Test-Path -LiteralPath $reopened) -or (Test-Path -LiteralPath $reportPath)) {
    throw "Refusing to overwrite existing candidate saved artifacts under $outputRootAbsolute"
}
if (Test-Path -LiteralPath $runDir) {
    throw "Refusing to reuse candidate saved run directory: $runDir"
}

function Gradle-Path([string]$path) {
    return [IO.Path]::GetFullPath($path).Replace('\', '/')
}

function Invoke-SavedCandidate([string]$output, [bool]$requireLiveNoise) {
    $liveNoise = $requireLiveNoise.ToString().ToLowerInvariant()
    & (Join-Path $repoRoot 'gradlew.bat') ':neoforge-1211:candidateLogicalSmoke' '--no-daemon' '--console=plain' `
        '-Dtellurium.candidate.capture=true' `
        '-Dtellurium.candidate.endpoint=SAVED' `
        '-Dtellurium.candidate.live=true' `
        "-Dtellurium.candidate.requireLiveNoise=$liveNoise" `
        "-Dtellurium.candidate.output=$(Gradle-Path $output)" `
        "-Dtellurium.candidate.runDir=$(Gradle-Path $runDir)" `
        "-Dtellurium.candidate.seed=$Seed" `
        "-Dtellurium.candidate.chunkX=$ChunkX" `
        "-Dtellurium.candidate.chunkZ=$ChunkZ" `
        "-Dtellurium.candidate.dimension=$Dimension"
    if ($LASTEXITCODE -ne 0) { throw "Candidate SAVED capture failed for $output with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $output)) { throw "Candidate SAVED capture did not produce $output" }
}

Write-Host "Capturing candidate SAVED endpoint into $runDir"
Invoke-SavedCandidate $expected $true
if (-not (Test-Path -LiteralPath $runDir)) {
    throw "Candidate server did not leave a saved run directory: $runDir"
}
Write-Host 'Reopening the same candidate saved world in a fresh server process'
Invoke-SavedCandidate $reopened $false

function Compare-Captures([string]$left, [string]$right, [string]$failureDir, [string]$label) {
    $compareArgs = "compare-captures `"$(Gradle-Path $left)`" `"$(Gradle-Path $right)`" --failure-dir=$(Gradle-Path $failureDir)"
    & (Join-Path $repoRoot 'gradlew.bat') ':oracle-and-replay:run' "--args=$compareArgs" '--no-daemon' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "$label comparison failed with exit code $LASTEXITCODE" }
}

Compare-Captures $reference $expected (Join-Path $outputRootAbsolute 'failures-reference') 'Candidate-vs-reference SAVED'
Compare-Captures $expected $reopened (Join-Path $outputRootAbsolute 'failures-reopen') 'Candidate fresh-process SAVED/reopened'
Compare-Captures $reference $reopened (Join-Path $outputRootAbsolute 'failures-reopened-reference') 'Reopened-vs-reference SAVED'

$referenceHash = (Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash
$expectedHash = (Get-FileHash -LiteralPath $expected -Algorithm SHA256).Hash
$reopenedHash = (Get-FileHash -LiteralPath $reopened -Algorithm SHA256).Hash
$result = [ordered]@{
    schemaVersion = 1
    kind = 'tellurium_candidate_saved_reopen'
    status = 'PASS_CANDIDATE_VERIFICATION_ONLY'
    seed = $Seed
    dimension = $Dimension
    chunkX = $ChunkX
    chunkZ = $ChunkZ
    runDirectory = $runDir
    reference = $reference
    expected = $expected
    reopened = $reopened
    referenceSha256 = $referenceHash
    expectedSha256 = $expectedHash
    reopenedSha256 = $reopenedHash
    candidateExpectedByteIdenticalToReference = $expectedHash -eq $referenceHash
    candidateReopenedByteIdenticalToExpected = $reopenedHash -eq $expectedHash
    candidateReopenedByteIdenticalToReference = $reopenedHash -eq $referenceHash
    note = 'One vanilla candidate SAVED endpoint with a real candidate NOISE task, an explicit logical save barrier, and a fresh-process candidate reopen. This does not qualify the production hook, the complete corpus, terrain mods, GPU, performance, or release status.'
}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "PASS candidate SAVED/reopened smoke reference=$reference expected=$expected reopened=$reopened"

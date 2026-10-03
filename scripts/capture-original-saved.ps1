param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
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
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('build\oracle-saved-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\oracle-runs\saved-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$outputRootAbsolute = [IO.Path]::GetFullPath($OutputRoot)
$runRootAbsolute = [IO.Path]::GetFullPath($RunRoot)
$rootsOverlap = $outputRootAbsolute -eq $runRootAbsolute -or $outputRootAbsolute.StartsWith($runRootAbsolute + [IO.Path]::DirectorySeparatorChar) -or $runRootAbsolute.StartsWith($outputRootAbsolute + [IO.Path]::DirectorySeparatorChar)
if ($rootsOverlap) {
    throw 'Saved oracle output and run roots must be distinct.'
}
New-Item -ItemType Directory -Force -Path $outputRootAbsolute, $runRootAbsolute | Out-Null

$expected = Join-Path $outputRootAbsolute 'expected.snap'
$reopened = Join-Path $outputRootAbsolute 'reopened.snap'
$runDir = Join-Path $runRootAbsolute 'saved-world'
if ((Test-Path -LiteralPath $expected) -or (Test-Path -LiteralPath $reopened)) {
    throw "Refusing to overwrite existing saved oracle artifacts under $outputRootAbsolute"
}
if (Test-Path -LiteralPath $runDir) {
    throw "Refusing to reuse saved oracle run directory: $runDir"
}

function Gradle-Path([string]$path) {
    return [IO.Path]::GetFullPath($path).Replace('\', '/')
}

function Invoke-SavedOracle([string]$output) {
    & (Join-Path $repoRoot 'gradlew.bat') ':oracle-1211:oracleSmoke' '--no-daemon' `
        "-Dworldgennext.oracle.capture=true" `
        "-Dworldgennext.oracle.output=$(Gradle-Path $output)" `
        "-Dworldgennext.oracle.runDir=$(Gradle-Path $runDir)" `
        "-Dworldgennext.oracle.seed=$Seed" `
        "-Dworldgennext.oracle.chunkX=$ChunkX" `
        "-Dworldgennext.oracle.chunkZ=$ChunkZ" `
        "-Dworldgennext.oracle.dimension=$Dimension" `
        "-Dworldgennext.oracle.endpoint=SAVED"
    if ($LASTEXITCODE -ne 0) { throw "Saved oracle capture failed for $output with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $output)) { throw "Saved oracle capture did not produce $output" }
}

Write-Host "Capturing original SAVED endpoint into $runDir"
Invoke-SavedOracle $expected
Write-Host 'Reopening the same saved world in a fresh server process'
Invoke-SavedOracle $reopened

$failureDir = Join-Path $outputRootAbsolute 'failures'
$compareArgs = "compare-captures `"$(Gradle-Path $expected)`" `"$(Gradle-Path $reopened)`" --failure-dir=$(Gradle-Path $failureDir)"
& (Join-Path $repoRoot 'gradlew.bat') ':oracle-and-replay:run' "--args=$compareArgs" '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "Fresh-process SAVED comparison failed with exit code $LASTEXITCODE" }
$expectedHash = (Get-FileHash -LiteralPath $expected -Algorithm SHA256).Hash
$reopenedHash = (Get-FileHash -LiteralPath $reopened -Algorithm SHA256).Hash

$result = [ordered]@{
    schemaVersion = 1
    kind = 'worldgennext_original_saved_reopen'
    status = 'PASS_REFERENCE_ONLY'
    seed = $Seed
    dimension = $Dimension
    chunkX = $ChunkX
    chunkZ = $ChunkZ
    runDirectory = $runDir
    expected = $expected
    reopened = $reopened
    expectedSha256 = $expectedHash
    reopenedSha256 = $reopenedHash
    byteIdentical = $expectedHash -eq $reopenedHash
    note = 'Original-vs-original fresh-process SAVED smoke; it does not qualify a WorldgenNext candidate or live hook.'
}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRootAbsolute 'saved-reopen-report.json') -Encoding UTF8
Write-Host "PASS original SAVED/reopened smoke expected=$expected reopened=$reopened"

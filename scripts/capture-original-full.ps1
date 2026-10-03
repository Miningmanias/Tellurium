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
    $OutputRoot = Join-Path $repoRoot ('build\oracle-full-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\oracle-runs\full-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$outputRootAbsolute = [IO.Path]::GetFullPath($OutputRoot)
$runRootAbsolute = [IO.Path]::GetFullPath($RunRoot)
$rootsOverlap = $outputRootAbsolute.Equals($runRootAbsolute, [StringComparison]::OrdinalIgnoreCase) -or
    $outputRootAbsolute.StartsWith($runRootAbsolute + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    $runRootAbsolute.StartsWith($outputRootAbsolute + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
if ($rootsOverlap) { throw 'Full oracle output and run roots must be distinct.' }
New-Item -ItemType Directory -Force -Path $outputRootAbsolute, $runRootAbsolute | Out-Null

$expected = Join-Path $outputRootAbsolute 'expected.snap'
$repeat = Join-Path $outputRootAbsolute 'repeat.snap'
$runDir = Join-Path $runRootAbsolute 'full-world'
if ((Test-Path -LiteralPath $expected) -or (Test-Path -LiteralPath $repeat)) {
    throw "Refusing to overwrite existing full oracle artifacts under $outputRootAbsolute"
}
if (Test-Path -LiteralPath $runDir) { throw "Refusing to reuse full oracle run directory: $runDir" }

function Gradle-Path([string]$path) {
    return [IO.Path]::GetFullPath($path).Replace('\', '/')
}

function Assert-FullCapture([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Full oracle capture did not produce $path" }
    $text = Get-Content -LiteralPath $path -Raw
    if ($text -notmatch '(?m)^WORLDGENNEXT-SNAPSHOT-1\r?\n') {
        throw "Full oracle capture has an invalid header: $path"
    }
    if ($text -notmatch '(?m)^identity=[^\r\n]*/FULL/[^\r\n]*$') {
        throw "Full oracle capture does not identify the FULL endpoint: $path"
    }
}

function Invoke-FullOracle([string]$output) {
    & (Join-Path $repoRoot 'gradlew.bat') ':oracle-1211:oracleSmoke' '--no-daemon' `
        "-Dworldgennext.oracle.capture=true" `
        "-Dworldgennext.oracle.output=$(Gradle-Path $output)" `
        "-Dworldgennext.oracle.runDir=$(Gradle-Path $runDir)" `
        "-Dworldgennext.oracle.seed=$Seed" `
        "-Dworldgennext.oracle.chunkX=$ChunkX" `
        "-Dworldgennext.oracle.chunkZ=$ChunkZ" `
        "-Dworldgennext.oracle.dimension=$Dimension" `
        "-Dworldgennext.oracle.endpoint=FULL"
    if ($LASTEXITCODE -ne 0) { throw "Full oracle capture failed for $output with exit code $LASTEXITCODE" }
    Assert-FullCapture $output
}

Write-Host "Capturing original FULL endpoint into $runDir"
Invoke-FullOracle $expected
Write-Host 'Repeating the same FULL capture in a fresh server process'
Invoke-FullOracle $repeat

$failureDir = Join-Path $outputRootAbsolute 'failures'
$compareArgs = "compare-captures `"$(Gradle-Path $expected)`" `"$(Gradle-Path $repeat)`" --failure-dir=$(Gradle-Path $failureDir)"
& (Join-Path $repoRoot 'gradlew.bat') ':oracle-and-replay:run' "--args=$compareArgs" '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "Fresh-process FULL comparison failed with exit code $LASTEXITCODE" }

$expectedHash = (Get-FileHash -LiteralPath $expected -Algorithm SHA256).Hash
$repeatHash = (Get-FileHash -LiteralPath $repeat -Algorithm SHA256).Hash
$result = [ordered]@{
    schemaVersion = 1
    kind = 'worldgennext_original_full_repeat'
    status = 'PASS_REFERENCE_ONLY'
    endpoint = 'FULL'
    seed = $Seed
    dimension = $Dimension
    chunkX = $ChunkX
    chunkZ = $ChunkZ
    runDirectory = $runDir
    expected = $expected
    repeat = $repeat
    expectedSha256 = $expectedHash
    repeatSha256 = $repeatHash
    byteIdentical = $expectedHash -eq $repeatHash
    note = 'Original-vs-original fresh-process FULL stability smoke; it does not qualify a WorldgenNext candidate or live hook.'
}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRootAbsolute 'full-repeat-report.json') -Encoding UTF8
Write-Host "PASS original FULL repeat smoke expected=$expected repeat=$repeat"

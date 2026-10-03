param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
    [ValidateSet('BIOMES', 'NOISE', 'SURFACE', 'CARVERS', 'FEATURES', 'FULL')]
    [string]$Status = 'NOISE',
    [int]$RadiusChunks = 45,
    [int]$WarmupRadiusChunks = 5,
    [int]$InFlight = 1024,
    [string]$Seed = '0',
    [string]$Label = 'vanilla',
    [string]$MaxHeap = '16G',
    [string]$Jfr = '',
    # Directory of terrain-mod jars copied into the fresh run's mods folder
    [string]$ModsDir = '',
    # Reopen an existing run directory (world is reused; chunks load from disk)
    [string]$ReopenRunDir = '',
    # Semicolon-separated extra -D properties, e.g. 'a=1;b=2'
    [string]$Properties = ''
)

# Fresh-world wall-clock chunk throughput run. Each invocation uses a new run
# directory and world, so measured chunks are never previously generated.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$runDir = Join-Path $repoRoot "build\run\bench-$Label-$($Status.ToLower())-$stamp"
$report = Join-Path $repoRoot "build\bench\$Label-$($Status.ToLower())-$stamp.json"
if ($ReopenRunDir) {
    $runDir = (Resolve-Path (Join-Path $repoRoot $ReopenRunDir)).Path
} elseif (Test-Path -LiteralPath $runDir) { throw "Refusing to reuse $runDir" }
$env:JAVA_HOME = $JavaHome
if ($ModsDir) {
    $mods = Join-Path $runDir 'mods'
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $repoRoot $ModsDir) -Filter '*.jar' | Copy-Item -Destination $mods
}

$gradleArgs = @(':neoforge-1211:runServer', '--no-daemon', '--console=plain',
    "-Dworldgennext.candidate.runDir=$runDir",
    "-Dworldgennext.candidate.seed=$Seed",
    "-Dworldgennext.run.maxHeap=$MaxHeap",
    '-Dworldgennext.bench.autorun=true',
    "-Dworldgennext.bench.status=$Status",
    "-Dworldgennext.bench.radiusChunks=$RadiusChunks",
    "-Dworldgennext.bench.warmupRadiusChunks=$WarmupRadiusChunks",
    "-Dworldgennext.bench.inFlight=$InFlight",
    "-Dworldgennext.bench.output=$report")
if ($ReopenRunDir) { $gradleArgs += '-Dworldgennext.prototype.resume=true' }
Write-Host "RUNDIR $runDir"
if ($Jfr) { $gradleArgs += "-Dworldgennext.run.jfr=$Jfr" }
foreach ($p in ($Properties -split ';' | Where-Object { $_ })) { $gradleArgs += "-D$p" }
$ErrorActionPreference = 'Continue'
& (Join-Path $repoRoot 'gradlew.bat') @gradleArgs 2>&1 | ForEach-Object { "$_" }
if (-not (Test-Path -LiteralPath $report)) { throw "No benchmark report written; see $runDir\logs\latest.log" }
Get-Content -LiteralPath $report

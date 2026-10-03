param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
    [string]$RunRoot = '',
    [string]$NoiseResultRoot = '',
    [ValidateRange(1, 8)]
    [int]$Workers = 2,
    [ValidateRange(1, 600000)]
    [int]$TimeoutMillis = 120000
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK 21 was not found at $java"
}

$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\run\prototype-cpu-live-' + $stamp)
}
if ([string]::IsNullOrWhiteSpace($NoiseResultRoot)) {
    $NoiseResultRoot = Join-Path $repoRoot ('build\prototype-cpu-live-' + $stamp + '\noise-results')
}
$runPath = [IO.Path]::GetFullPath($RunRoot)
$resultPath = [IO.Path]::GetFullPath($NoiseResultRoot)
if (Test-Path -LiteralPath $runPath) {
    throw "Refusing to reuse prototype server run root: $runPath"
}
if (Test-Path -LiteralPath $resultPath) {
    throw "Refusing to reuse prototype NOISE result root: $resultPath"
}

$gradlew = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew -PathType Leaf)) {
    throw "Gradle wrapper was not found: $gradlew"
}

$env:JAVA_HOME = $JavaHome
Write-Host "Starting WorldgenNext CPU-live prototype"
Write-Host "  server run: $runPath"
Write-Host "  NOISE artifacts: $resultPath"
Write-Host "  workers: $Workers"
Write-Host "  startup timeout: $TimeoutMillis ms"
Write-Host 'Stop the server with the Minecraft stop command or Ctrl+C.'

& $gradlew ':neoforge-1211:runServer' '--no-daemon' '--console=plain' `
    "-Dworldgennext.candidate.runDir=$runPath" `
    '-Dworldgennext.prototype.cpuLive=true' `
    "-Dworldgennext.prototype.cpuWorkers=$Workers" `
    "-Dworldgennext.prototype.cpuLiveTimeoutMillis=$TimeoutMillis" `
    "-Dworldgennext.candidate.liveNoiseResultDir=$resultPath"
$serverExitCode = $LASTEXITCODE
$latestLog = Join-Path $runPath 'logs\latest.log'
$ready = $false
if (Test-Path -LiteralPath $latestLog -PathType Leaf) {
    $ready = [bool](Select-String -LiteralPath $latestLog -Pattern 'Done \([0-9]+\.[0-9]+s\)!' -Quiet)
}
$artifacts = @()
if (Test-Path -LiteralPath $resultPath -PathType Container) {
    $artifacts = @(Get-ChildItem -LiteralPath $resultPath -Filter '*.chunk' -File)
}
$reportDirectory = Split-Path -Parent $resultPath
$reportPath = Join-Path $reportDirectory 'cpu-live-report.json'
$report = [ordered]@{
    schemaVersion = 1
    kind = 'worldgennext_cpu_live_prototype'
    status = if ($ready -and $artifacts.Count -gt 0) { 'PASS_PROTOTYPE_WIRING_ONLY' } else { 'FAIL' }
    serverExitCode = $serverExitCode
    serverRunRoot = $runPath
    latestLog = $latestLog
    noiseResultRoot = $resultPath
    serverReachedReady = $ready
    noiseArtifactCount = $artifacts.Count
    noiseArtifactBytes = [long](($artifacts | Measure-Object -Property Length -Sum).Sum)
    workers = $Workers
    timeoutMillis = $TimeoutMillis
    note = 'The opt-in CPU route reached ordinary server readiness and emitted live NOISE artifacts. This is prototype wiring/playability evidence only; it is not independent candidate parity, G9 qualification, GPU evidence, or G12 release evidence.'
}
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
if (-not $ready) {
    throw "CPU-live prototype did not reach server readiness; see $latestLog (exit code $serverExitCode)"
}
Write-Host "CPU-live prototype stopped. Run artifacts remain at $runPath"
Write-Host "CPU-live prototype report: $reportPath (artifacts=$($artifacts.Count), serverReady=$ready)"

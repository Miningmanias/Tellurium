[CmdletBinding()]
param(
    [long]$Seed = 1,
    [ValidateSet('minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end')]
    [string]$Dimension = 'minecraft:overworld',
    [int]$ChunkX = 32,
    [int]$ChunkZ = 32,
    [string]$Point = '512,64,520',
    [string]$Root = 'wg_spline_82',
    [switch]$SeedCpuChildren,
    [switch]$StageCpuOracle,
    [ValidateRange(0, 1)]
    [double]$StageOracleTolerance = 0.000001,
    [switch]$FullChunk,
    [switch]$DeviceOnly,
    [switch]$MaterialPipelineProbe,
    [switch]$SharedSpline,
    [switch]$SharedEndIsland,
    [switch]$ResidentNormalNoise,
    [switch]$ExactProfile,
    [switch]$SharedBlended,
    [switch]$InlineIeeeHelpers,
    [switch]$EnablePipelineOptimization,
    [ValidateRange(1, 4000000)]
    [int]$MaxShaderSourceChars = 900000,
    [ValidateRange(1, 16384)]
    [int]$BatchElements = 1,
    [string]$SemanticNodes = '',
    [string]$OutputRoot = '',
    [string]$RunRoot = '',
    [ValidateRange(1, 180)]
    [int]$TimeoutMinutes = 6,
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

function Test-SameOrChild([string]$Path, [string]$Parent) {
    $normalizedPath = $Path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $Parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Gradle-ArgsPath([string]$Path) {
    return $Path.Replace('\', '/')
}

function Quote-PowerShellLiteral([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

if ($Point -notmatch '^\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*$') {
    throw 'Point must be three comma-separated signed integers: x,y,z.'
}
$pointX = [int]$Matches[1]
$pointY = [int]$Matches[2]
$pointZ = [int]$Matches[3]
$pointValue = "$pointX,$pointY,$pointZ"
if (-not $FullChunk -and ([Math]::Floor($pointX / 16.0) -ne $ChunkX -or [Math]::Floor($pointZ / 16.0) -ne $ChunkZ)) {
    throw "Point $pointValue is outside chunk ($ChunkX,$ChunkZ)."
}
if ($FullChunk -and ($SeedCpuChildren -or $StageCpuOracle -or $SemanticNodes)) {
    throw 'FullChunk cannot be combined with CPU seeding or stage/semantic diagnostics.'
}
if ($DeviceOnly -and (-not $FullChunk -or -not $ExactProfile)) {
    throw 'DeviceOnly requires FullChunk and ExactProfile; it produces execution evidence, not a parity verdict.'
}
if ($MaterialPipelineProbe -and ($FullChunk -or $SeedCpuChildren)) {
    throw 'MaterialPipelineProbe is a one-point GPU-only pipeline diagnostic, not FullChunk or CPU seeding.'
}
if ($FullChunk -and -not $PSBoundParameters.ContainsKey('BatchElements')) { $BatchElements = 4096 }
$probePoint = if ($FullChunk) { '' } else { $pointValue }
$probeRoot = if ($FullChunk -or $MaterialPipelineProbe) { '' } else { $Root }
$densityParityDiagnostic = (-not $FullChunk).ToString().ToLowerInvariant()
$nativeDraft = (-not $ExactProfile).ToString().ToLowerInvariant()
$numericProfile = if ($ExactProfile) { 'GPU_IEEE_BITS' } else { 'GPU_NATIVE_DRAFT' }
$expectedReceiptStatus = if ($DeviceOnly) { 'DEVICE_EXECUTION_ONLY' } elseif ($ExactProfile) { 'PASS' } else { 'DRAFT_PARITY_PASS' }
if ($Root -notmatch '^(wg_node_[0-9]+|wg_spline_[0-9]+|semantic:[0-9a-fA-F]{64})$') {
    throw 'Root must be wg_node_<id>, wg_spline_<id>, or semantic:<64-hex-node-fingerprint>.'
}
if (-not [string]::IsNullOrWhiteSpace($SemanticNodes)) {
    foreach ($node in $SemanticNodes.Split(',')) {
        if ($node.Trim() -notmatch '^(wg_node_[0-9]+|wg_spline_[0-9]+|semantic:[0-9a-fA-F]{64})$') {
            throw "Invalid semantic node selector: $node"
        }
    }
    $SemanticNodes = ($SemanticNodes.Split(',') | ForEach-Object { $_.Trim() }) -join ','
}

$javaHomePath = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
$javaRelease = Join-Path $javaHomePath 'release'
if (-not (Test-Path -LiteralPath $javaRelease -PathType Leaf) -or
    (Get-Content -LiteralPath $javaRelease -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.[^"]*)?"\s*$') {
    throw "JavaHome must point to a Java 21 JDK: $javaHomePath"
}
$env:JAVA_HOME = $javaHomePath

$runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "build\gpu-density-probes\$runId"
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot "build\run\gpu-density-probes\$runId"
}
$outputs = [IO.Path]::GetFullPath($OutputRoot)
$runs = [IO.Path]::GetFullPath($RunRoot)
foreach ($path in @($outputs, $runs)) {
    if ($path -match '[\r\n\t]') { throw "Output and run paths must not contain tabs or newlines: $path" }
    if (Test-Path -LiteralPath $path) { throw "Refusing to reuse an existing output/run path: $path" }
}
if ((Test-SameOrChild $outputs $runs) -or (Test-SameOrChild $runs $outputs)) {
    throw 'OutputRoot and RunRoot must be distinct and non-overlapping.'
}

$manifest = Join-Path $outputs 'cases.tsv'
$artifact = Join-Path $outputs ("seed-{0}-x-{1}-z-{2}-NOISE.chunk" -f $Seed, $ChunkX, $ChunkZ)
$stdoutPath = Join-Path $runs 'stdout.log'
$stderrPath = Join-Path $runs 'stderr.log'
$statusPath = Join-Path $outputs 'diagnostic-status.json'
$workerExitPath = Join-Path $runs 'worker-exit-code.txt'
$caseLine = "$ChunkX" + [char]9 + "$ChunkZ" + [char]9 + "$artifact"
[IO.Directory]::CreateDirectory($outputs) | Out-Null
[IO.Directory]::CreateDirectory($runs) | Out-Null
[IO.File]::WriteAllLines($manifest, [string[]]@($caseLine), [Text.UTF8Encoding]::new($false))

$candidateTimeoutMillis = [long]$TimeoutMinutes * 60 * 1000
$gradleArgs = @(
    ':neoforge-1211:gpuCandidateSmoke',
    '--no-daemon',
    '--console=plain',
    '-Dtellurium.candidate.capture=true',
    '-Dtellurium.candidate.output=',
    "-Dtellurium.candidate.caseFile=$(Gradle-ArgsPath $manifest)",
    "-Dtellurium.candidate.runDir=$(Gradle-ArgsPath $runs)",
    "-Dtellurium.candidate.seed=$Seed",
    "-Dtellurium.candidate.chunkX=$ChunkX",
    "-Dtellurium.candidate.chunkZ=$ChunkZ",
    '-Dtellurium.candidate.endpoint=NOISE',
    "-Dtellurium.candidate.dimension=$Dimension",
    "-Dtellurium.candidate.timeoutMillis=$candidateTimeoutMillis",
    '-Dtellurium.gpuCandidate.capture=true',
    "-Dtellurium.gpuCandidate.deviceOnly=$($DeviceOnly.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.batchElements=$BatchElements",
    "-Dtellurium.gpuCandidate.maxShaderSourceChars=$MaxShaderSourceChars",
    "-Dtellurium.gpuCandidate.sharedSplineShader=$($SharedSpline.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.endIslandSharedShader=$($SharedEndIsland.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.blendedNoiseSharedShader=$($SharedBlended.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.nativeDraft=$nativeDraft",
    "-Dtellurium.gpuCandidate.enablePipelineOptimization=$($EnablePipelineOptimization.IsPresent.ToString().ToLowerInvariant())",
    '-Dtellurium.gpuCandidate.nativeDraftArithmetic=both',
    '-Dtellurium.gpuCandidate.normalNoiseSharedShader=true',
    "-Dtellurium.gpuCandidate.sharedNormalNoiseResidentChain=$($ResidentNormalNoise.IsPresent.ToString().ToLowerInvariant())",
    '-Dtellurium.gpuCandidate.normalNoiseSharedGenericShader=true',
    '-Dtellurium.gpuCandidate.rewriteCapturedNoiseSampler=true',
    '-Dtellurium.gpuCandidate.exactAquiferStage=true',
    '-Dtellurium.gpuCandidate.exactAquiferBarrierInput=true',
    '-Dtellurium.gpuCandidate.debugStages=true',
    "-Dtellurium.gpuCandidate.debugStagesFile=$(Gradle-ArgsPath (Join-Path $outputs 'stages.log'))",
    "-Dtellurium.gpuCandidate.stagedShaderDir=$(Gradle-ArgsPath (Join-Path $outputs 'shaders'))",
    "-Dtellurium.gpuCandidate.debugDensityParity=$densityParityDiagnostic",
    "-Dtellurium.gpuCandidate.debugDensityProbePoint=$probePoint",
    "-Dtellurium.gpuCandidate.debugDensityStageRoot=$probeRoot",
    "-Dtellurium.gpuCandidate.debugDensityStageRootSeedCpu=$($SeedCpuChildren.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.debugDensityStageCpuOracle=$($StageCpuOracle.IsPresent.ToString().ToLowerInvariant())",
    "-Dtellurium.gpuCandidate.debugDensityStageCpuOracleTolerance=$($StageOracleTolerance.ToString('R', [Globalization.CultureInfo]::InvariantCulture))",
    "-Dtellurium.gpuCandidate.debugDensitySemanticNodes=$SemanticNodes"
)
if ($InlineIeeeHelpers) {
    $gradleArgs += '-Dtellurium.gpuCandidate.densityDontInlinePrefix=wg_node_,wg_spline_'
}

$gradle = Join-Path $repoRoot 'gradlew.bat'
$powershellExe = (Get-Process -Id $PID).Path
$workerCommand = '& ' + (Quote-PowerShellLiteral $gradle) + ' ' +
    (($gradleArgs | ForEach-Object { Quote-PowerShellLiteral $_ }) -join ' ') +
    [Environment]::NewLine + '$gpuWorkerExitCode = $LASTEXITCODE' +
    [Environment]::NewLine + '[IO.File]::WriteAllText(' + (Quote-PowerShellLiteral $workerExitPath) + ', [string]$gpuWorkerExitCode)' +
    [Environment]::NewLine + 'exit $gpuWorkerExitCode'
$encodedWorker = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($workerCommand))
$workerArguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encodedWorker"

$process = $null
$replayTimer = [Diagnostics.Stopwatch]::StartNew()
$processExitCode = $null
$timedOut = $false
$treeKillAttempted = $false
$treeKillExitCode = $null
$launchError = $null
$cleanupError = $null
$processExitCodeSource = 'process'
try {
    $process = Start-Process -FilePath $powershellExe -ArgumentList $workerArguments -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $timeoutMs = [int]($TimeoutMinutes * 60 * 1000)
    if (-not $process.WaitForExit($timeoutMs)) {
        $process.Refresh()
        if (-not $process.HasExited) {
            $timedOut = $true
            $treeKillAttempted = $true
            $taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
            & $taskkill /PID $process.Id /T /F | Out-Null
            $treeKillExitCode = $LASTEXITCODE
            [void]$process.WaitForExit(15000)
        }
    }
    $process.Refresh()
    if ($process.HasExited) {
        # WaitForExit(timeout) can return before redirected stream events drain.
        $process.WaitForExit()
        $processExitCode = $process.ExitCode
    }
} catch {
    $launchError = $_.Exception.Message
    if ($null -ne $process) {
        try {
            $process.Refresh()
            if (-not $process.HasExited) {
                $treeKillAttempted = $true
                & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID $process.Id /T /F | Out-Null
                $treeKillExitCode = $LASTEXITCODE
                [void]$process.WaitForExit(15000)
            }
            $process.Refresh()
            if ($process.HasExited) { $process.WaitForExit(); $processExitCode = $process.ExitCode }
        } catch { $cleanupError = $_.Exception.Message }
    }
} finally {
    if ($null -ne $process) { $process.Dispose() }
    $replayTimer.Stop()
}

# Start-Process can expose a null ExitCode on Windows despite a completed
# redirected child. Use only the child's explicit exit record, never a log
# success marker or a passing receipt, to recover that status.
if ($null -eq $processExitCode -and (Test-Path -LiteralPath $workerExitPath)) {
    $workerExitText = [string](Get-Content -LiteralPath $workerExitPath -Raw)
    if ($workerExitText.Trim() -match '^-?\d+$') {
        $processExitCode = [int]$workerExitText.Trim()
        $processExitCodeSource = 'worker-exit-record'
    }
}

[string]$stdoutText = ''
[string]$stderrText = ''
if (Test-Path -LiteralPath $stdoutPath) { $stdoutText = [string](Get-Content -LiteralPath $stdoutPath -Raw) }
if (Test-Path -LiteralPath $stderrPath) { $stderrText = [string](Get-Content -LiteralPath $stderrPath -Raw) }
$expectedMarker = if ($MaterialPipelineProbe) { "GPU density probe completed for point=$pointValue" } else { "GPU staged density root diagnostic root=$Root resolved=" }
$escapedMarker = [regex]::Escape($expectedMarker)
$diagnosticStopFound = ($stdoutText -match $escapedMarker) -or ($stderrText -match $escapedMarker)
$receiptPath = $artifact + '.gpu-receipt.json'
$receipt = if (Test-Path -LiteralPath $receiptPath) { Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json } else { $null }
$stageLog = Join-Path $outputs 'stages.log'
$sharedSplineStages = if (Test-Path -LiteralPath $stageLog) { @(Select-String -LiteralPath $stageLog -Pattern '^density-shared-spline root=').Count } else { 0 }
$sharedPerlinSuffixStages = if (Test-Path -LiteralPath $stageLog) { @(Select-String -LiteralPath $stageLog -Pattern '^density-normal-perlin-complete .*metadataLayout=batch-suffix').Count } else { 0 }
$sharedBlendedSamples = if (Test-Path -LiteralPath $stageLog) { @(Select-String -LiteralPath $stageLog -Pattern '^density-blended-shared-sample-complete ').Count } else { 0 }
$residentNormalNoiseStages = if (Test-Path -LiteralPath $stageLog) { @(Select-String -LiteralPath $stageLog -Pattern '^density-normal-noise-resident root=').Count } else { 0 }
$sharedEndIslandStages = if (Test-Path -LiteralPath $stageLog) { @(Select-String -LiteralPath $stageLog -Pattern '^density-end-island-shared root=').Count } else { 0 }
$fullPassed = $FullChunk -and $processExitCode -eq 0 -and $receipt -and
    $receipt.status -eq $expectedReceiptStatus -and $receipt.route -eq $numericProfile -and
    (Test-Path -LiteralPath $artifact) -and $(if ($DeviceOnly) {
        $receipt.logicalGpuElements -gt 0 -and $receipt.comparedBlocks -eq 0 -and $null -eq $receipt.mismatches
    } else { $receipt.comparedBlocks -gt 0 -and $receipt.mismatches -eq 0 })
$sharedRouteObserved = -not ($SharedSpline -and $Dimension -eq 'minecraft:overworld') -or $sharedSplineStages -gt 0
$sharedRouteObserved = $sharedRouteObserved -and (-not $SharedBlended -or $sharedBlendedSamples -gt 0)
$sharedRouteObserved = $sharedRouteObserved -and (-not ($ResidentNormalNoise -and $Dimension -eq 'minecraft:overworld') -or $residentNormalNoiseStages -gt 0)
$sharedRouteObserved = $sharedRouteObserved -and (-not ($SharedEndIsland -and $Dimension -eq 'minecraft:the_end') -or $sharedEndIslandStages -gt 0)
$status = if ($timedOut) {
    'TIMEOUT'
} elseif ($fullPassed -and $sharedRouteObserved) {
    if ($DeviceOnly) { 'DEVICE_EXECUTION_ONLY' } elseif ($ExactProfile) { 'EXACT_PROFILE_PARITY_PASS' } else { 'DRAFT_PARITY_PASS' }
} elseif ($fullPassed) {
    'REQUESTED_SHARED_ROUTE_NOT_OBSERVED'
} elseif ($diagnosticStopFound) {
    'EXPECTED_DIAGNOSTIC_STOP'
} elseif (-not [string]::IsNullOrWhiteSpace($launchError)) {
    'LAUNCH_FAILED'
} elseif ($processExitCode -eq 0) {
    'DIAGNOSTIC_STOP_NOT_OBSERVED'
} else {
    'FAILED_BEFORE_DIAGNOSTIC_STOP'
}

$report = [ordered]@{
    schemaVersion = 1
    kind = if ($DeviceOnly) { 'tellurium_gpu_isolated_device_execution' } elseif ($FullChunk) { 'tellurium_gpu_isolated_full_replay' } else { 'tellurium_gpu_density_probe' }
    status = $status
    gpuParity = if ($DeviceOnly) { 'NOT_ASSESSED_NO_CPU_COMPARISON' } elseif ($fullPassed) { if ($ExactProfile) { 'EXACT_PROFILE_GPU_VS_CPU_PARITY_ONLY' } else { 'DRAFT_GPU_VS_CPU_PARITY_ONLY' } } else { 'NOT_ASSESSED_DIAGNOSTIC_ONLY' }
    note = 'Isolated full replay compares GPU output with the owned CPU result, not an independent Minecraft oracle. One-case exact-profile receipts, diagnostic stops, native draft receipts, and timings do not qualify the G6 corpus or live hook.'
    diagnosticStopFound = $diagnosticStopFound
    seed = $Seed
    dimension = $Dimension
    chunkX = $ChunkX
    chunkZ = $ChunkZ
    point = if ($FullChunk) { $null } else { $pointValue }
    root = if ($FullChunk -or $MaterialPipelineProbe) { $null } else { $Root }
    materialPipelineProbe = $MaterialPipelineProbe.IsPresent
    cpuChildSeeding = $SeedCpuChildren.IsPresent
    stageCpuOracle = $StageCpuOracle.IsPresent
    stageOracleTolerance = $StageOracleTolerance
    fullChunk = $FullChunk.IsPresent
    deviceOnly = $DeviceOnly.IsPresent
    batchElements = $BatchElements
    sharedSplineRequested = $SharedSpline.IsPresent
    sharedSplineStages = $sharedSplineStages
    sharedEndIslandRequested = $SharedEndIsland.IsPresent
    sharedEndIslandStages = $sharedEndIslandStages
    sharedBlendedRequested = $SharedBlended.IsPresent
    sharedBlendedSamples = $sharedBlendedSamples
    inlineIeeeHelpersRequested = $InlineIeeeHelpers.IsPresent
    pipelineOptimizationRequested = $EnablePipelineOptimization.IsPresent
    maxShaderSourceChars = $MaxShaderSourceChars
    sharedPerlinSuffixStages = $sharedPerlinSuffixStages
    elapsedMillis = $replayTimer.ElapsedMilliseconds
    executorTelemetry = if ($receipt) { $receipt.telemetry } else { $null }
    compilationTelemetry = if ($receipt) { $receipt.compilationTelemetry } else { $null }
    spirvCacheTelemetry = if ($receipt) { $receipt.spirvCacheTelemetry } else { $null }
    receipt = $receiptPath
    semanticNodes = $SemanticNodes
    numericProfile = $numericProfile
    sharedPerlinSampler = 'normalNoiseSharedShader=true'
    residentNormalNoiseRequested = $ResidentNormalNoise.IsPresent
    residentNormalNoiseStages = $residentNormalNoiseStages
    exactAquiferStage = $true
    exactAquiferBarrierInput = $true
    outputRoot = $outputs
    runRoot = $runs
    caseManifest = $manifest
    expectedCandidateArtifact = $artifact
    stdoutLog = $stdoutPath
    stderrLog = $stderrPath
    processExitCode = $processExitCode
    processExitCodeSource = $processExitCodeSource
    workerExitRecord = $workerExitPath
    timedOut = $timedOut
    processTreeKillAttempted = $treeKillAttempted
    processTreeKillExitCode = $treeKillExitCode
    launchError = $launchError
    cleanupError = $cleanupError
}
[IO.File]::WriteAllText($statusPath, ($report | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))

Write-Host "Diagnostic status: $status"
Write-Host "GPU parity status: $($report.gpuParity)"
Write-Host "OutputRoot: $outputs"
Write-Host "RunRoot: $runs"
Write-Host "Status report: $statusPath"
Write-Host "Stdout log: $stdoutPath"
Write-Host "Stderr log: $stderrPath"

if (($diagnosticStopFound -or ($fullPassed -and $sharedRouteObserved)) -and -not $timedOut) { exit 0 }
if (-not [string]::IsNullOrWhiteSpace($launchError)) {
    Write-Error "GPU density diagnostic launcher failed: $launchError"
}
if ($timedOut) {
    Write-Error "Timed out after $TimeoutMinutes minutes; attempted to stop only the launched PowerShell/Gradle/Minecraft process tree."
}
if ($FullChunk) {
    Write-Error 'Full isolated GPU replay failed or its requested shared route was not observed; inspect retained logs and receipt.'
} else {
    Write-Error 'The expected fail-closed staged density-root diagnostic stop was not found in the retained logs.'
}
exit 1

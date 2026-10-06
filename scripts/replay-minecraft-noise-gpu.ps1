param(
    [string]$ExpectedRoot = (Join-Path $PSScriptRoot '..\build\oracle-captures\original'),
    [string]$OutputRoot = '',
    [string]$RunRoot = '',
    [int]$BatchElements = 128,
    [switch]$SharedStages,
    [switch]$SharedEndIsland,
    [switch]$EnablePipelineOptimization,
    [switch]$ResidentNormalNoise,
    [switch]$TraceStages,
    [switch]$FreezeCompiledInputs,
    [ValidateRange(1, 4000000)]
    [int]$MaxShaderSourceChars = 900000,
    [string]$CaseFilter = '',
    [ValidateRange(1, 180)]
    [int]$TimeoutMinutesPerGroup = 12,
    [string]$ModDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$expected = (Resolve-Path $ExpectedRoot -ErrorAction Stop).Path
if ($FreezeCompiledInputs -and $PSVersionTable.PSVersion.Major -lt 7) {
    throw 'FreezeCompiledInputs requires PowerShell 7 (pwsh).'
}
$frozenModules = @('semantic-core', 'compiler-jvm', 'compiler-vulkan', 'material-codec',
    'spatial-data', 'chunk-engine', 'frontend-mc1211', 'runtime-vulkan', 'neoforge-1211')
$frozenGradleArgs = @()
function Get-CompiledInputsManifest {
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($module in $frozenModules) {
        $classes = Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/classes/java/main"
        if (-not (Test-Path -LiteralPath $classes -PathType Container)) {
            throw "Frozen replay requires existing compiled classes: $classes"
        }
        foreach ($kind in @('classes/java/main', 'resources/main')) {
            $directory = Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/$kind"
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -File -Recurse)) {
                $relative = [IO.Path]::GetRelativePath($directory, $file.FullName).Replace('\', '/')
                $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                $lines.Add("$module/$kind/$relative`t$($file.Length)`t$hash")
            }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    return ($lines -join "`n") + "`n"
}
function Get-ManifestHash([string]$manifest) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($manifest))) }
    finally { $sha.Dispose() }
}
$frozenManifest = ''
$frozenCompiledInputsHash = $null
if ($FreezeCompiledInputs) {
    $frozenManifest = Get-CompiledInputsManifest
    $frozenCompiledInputsHash = Get-ManifestHash $frozenManifest
    foreach ($module in $frozenModules) {
        $frozenGradleArgs += @('-x', ":${module}:compileJava", '-x', ":${module}:processResources")
    }
    Write-Host "Frozen compiled inputs SHA256=$frozenCompiledInputsHash"
}
function Assert-FrozenCompiledInputs {
    if ($FreezeCompiledInputs -and (Get-ManifestHash (Get-CompiledInputsManifest)) -ne $frozenCompiledInputsHash) {
        throw 'Frozen compiled classes/resources changed during replay; refusing mixed-snapshot success.'
    }
}
if ($BatchElements -lt 1 -or $BatchElements -gt 16384) {
    throw 'BatchElements must be in [1,16384]'
}
if ($ResidentNormalNoise -and -not $SharedStages) {
    throw 'ResidentNormalNoise requires SharedStages and does not qualify the selected route.'
}
if (-not [string]::IsNullOrWhiteSpace($CaseFilter)) {
    try { [regex]::new($CaseFilter) | Out-Null }
    catch { throw "CaseFilter must be a valid regular expression: $CaseFilter" }
}

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('build\gpu-candidates\' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\run\gpu-candidates-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$outputs = [IO.Path]::GetFullPath($OutputRoot)
$runs = [IO.Path]::GetFullPath($RunRoot)
function Test-SameOrChild([string]$path, [string]$parent) {
    $normalizedPath = $path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
foreach ($root in @($outputs, $runs)) {
    if (Test-SameOrChild $root $expected) {
        throw 'GPU candidate output/run roots must not be the independent oracle directory or a child of it.'
    }
}
if (Test-SameOrChild $outputs $runs -or Test-SameOrChild $runs $outputs) {
    throw 'GPU candidate output and run roots must be distinct.'
}
$modFiles = @()
if (-not [string]::IsNullOrWhiteSpace($ModDirectory)) {
    $modRoot = (Resolve-Path -LiteralPath $ModDirectory -ErrorAction Stop).Path
    if (-not (Get-Item -LiteralPath $modRoot).PSIsContainer) { throw "ModDirectory is not a directory: $modRoot" }
    $modFiles = @(Get-ChildItem -LiteralPath $modRoot -Filter '*.jar' -File | Sort-Object Name)
    if ($modFiles.Count -eq 0) { throw "ModDirectory contains no jar files: $modRoot" }
}
New-Item -ItemType Directory -Force -Path $outputs, $runs | Out-Null
if ($FreezeCompiledInputs) {
    [IO.File]::WriteAllText((Join-Path $runs 'frozen-compiled-inputs.tsv'), $frozenManifest, [Text.UTF8Encoding]::new($false))
}

$captures = @(Get-ChildItem -LiteralPath $expected -Filter '*.snap' -File -Recurse | Sort-Object FullName)
if ($captures.Count -eq 0) { throw "No independent oracle captures found in $expected" }
$gradle = Join-Path $repoRoot 'gradlew.bat'
$results = [System.Collections.Generic.List[object]]::new()
$caseSpecs = [System.Collections.Generic.List[object]]::new()

function Gradle-ArgsPath([string]$path) {
    return $path.Replace('\', '/')
}
function Quote-WorkerLiteral([string]$value) { return "'" + $value.Replace("'", "''") + "'" }
function Invoke-GpuGroup([string[]]$arguments, [string]$directory) {
    $stdout = Join-Path $directory 'stdout.log'
    $stderr = Join-Path $directory 'stderr.log'
    $exitRecord = Join-Path $directory 'worker-exit-code.txt'
    $command = '& ' + (Quote-WorkerLiteral $gradle) + ' ' +
        (($arguments | ForEach-Object { Quote-WorkerLiteral $_ }) -join ' ') +
        [Environment]::NewLine + '$gpuGroupExitCode = $LASTEXITCODE' +
        [Environment]::NewLine + '[IO.File]::WriteAllText(' + (Quote-WorkerLiteral $exitRecord) + ', [string]$gpuGroupExitCode)' +
        [Environment]::NewLine + 'exit $gpuGroupExitCode'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $executable = (Get-Process -Id $PID).Path
    $process = $null
    $processId = $null
    $exitCode = $null
    $exited = $false
    $timedOut = $false
    $killAttempted = $false
    $killExitCode = $null
    $launchError = $null
    $cleanupError = $null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $process = Start-Process -FilePath $executable -ArgumentList "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded" -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $processId = $process.Id
        while (-not $process.WaitForExit(1000)) {
            if ($timer.Elapsed.TotalMinutes -ge $TimeoutMinutesPerGroup) {
                $process.Refresh()
                if (-not $process.HasExited) {
                    $timedOut = $true
                    $killAttempted = $true
                    & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID $process.Id /T /F | Out-Null
                    $killExitCode = $LASTEXITCODE
                    [void]$process.WaitForExit(15000)
                }
                break
            }
        }
        $process.Refresh()
        $exited = $process.HasExited
        if ($exited) { $process.WaitForExit(); $exitCode = $process.ExitCode }
    } catch {
        $launchError = $_.Exception.Message
        # An exception after launch must not leave an unowned GPU worker running
        # while the serial runner advances. Stop only this known PID's tree.
        if ($null -ne $process) {
            try {
                $process.Refresh()
                if (-not $process.HasExited) {
                    $killAttempted = $true
                    & (Join-Path $env:SystemRoot 'System32\taskkill.exe') /PID $process.Id /T /F | Out-Null
                    $killExitCode = $LASTEXITCODE
                    [void]$process.WaitForExit(15000)
                }
                $process.Refresh()
                $exited = $process.HasExited
                if ($exited) { $process.WaitForExit(); $exitCode = $process.ExitCode }
            } catch { $cleanupError = $_.Exception.Message }
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        $timer.Stop()
    }
    if ($null -eq $exitCode -and (Test-Path -LiteralPath $exitRecord)) {
        $record = [string](Get-Content -LiteralPath $exitRecord -Raw)
        if ($record.Trim() -match '^-?\d+$') { $exitCode = [int]$record.Trim() }
    }
    $status = [ordered]@{
        schemaVersion = 1
        kind = 'worldgennext_gpu_group_process'
        status = if ($timedOut) { 'TIMEOUT' } elseif ($exited -and $exitCode -eq 0 -and -not $launchError) { 'PROCESS_COMPLETED' } else { 'PROCESS_FAILED' }
        note = 'Process completion is not artifact, oracle or qualification success.'
        ownedWorkerPid = $processId
        workerExited = $exited
        exitCode = $exitCode
        elapsedMillis = $timer.ElapsedMilliseconds
        timeoutMinutes = $TimeoutMinutesPerGroup
        timedOut = $timedOut
        processTreeKillAttempted = $killAttempted
        processTreeKillExitCode = $killExitCode
        launchError = $launchError
        cleanupError = $cleanupError
        stdoutLog = $stdout
        stderrLog = $stderr
        workerExitRecord = $exitRecord
    }
    $status | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $directory 'group-run-status.json') -Encoding UTF8
    if ($status.status -ne 'PROCESS_COMPLETED') {
        if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Tail 16 | Write-Host }
        throw "GPU case-group process failed: $($status.status); see $directory/group-run-status.json"
    }
    Write-Host "GPU group process completed: elapsedMillis=$($timer.ElapsedMilliseconds) logs=$directory"
}
function Write-CaseManifest([string]$path, [object[]]$cases) {
    $lines = @($cases | ForEach-Object { "$($_.chunkX)" + [char]9 + "$($_.chunkZ)" + [char]9 + [IO.Path]::GetFullPath($_.artifact) })
    [IO.File]::WriteAllLines($path, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}

function Snapshot-Dimension([string]$path) {
    $line = Get-Content -LiteralPath $path | Where-Object { $_ -like 'value=dimension=*' } | Select-Object -First 1
    if (-not $line) { throw "Capture is missing value=dimension: $path" }
    $value = $line.Substring('value=dimension='.Length).Replace('\\=', '=').Replace('\\\\', '\')
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Capture has a blank dimension: $path" }
    return $value
}

function Relative-CapturePath([string]$root, [string]$path) {
    $prefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Capture is outside the expected corpus root: $path"
    }
    $relative = $path.Substring($prefix.Length)
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('..\') -or $relative.StartsWith('../')) {
        throw "Capture path escapes the expected corpus root: $path"
    }
    return $relative
}

foreach ($capture in $captures) {
    if ($capture.BaseName -notmatch '^seed-(?<seed>-?\d+)-x-(?<x>-?\d+)-z-(?<z>-?\d+)-NOISE$') {
        throw "Capture filename is not a signed NOISE case key: $($capture.Name)"
    }
    $seed = $Matches.seed
    $chunkX = $Matches.x
    $chunkZ = $Matches.z
    $relativeCapture = Relative-CapturePath $expected $capture.FullName
    if ($CaseFilter -and $relativeCapture.Replace('\', '/') -notmatch $CaseFilter) { continue }
    $dimension = Snapshot-Dimension $capture.FullName
    $relativeCase = $relativeCapture -replace '\.snap$', ''
    $artifact = Join-Path $outputs ($relativeCase + '.chunk')
    $receipt = $artifact + '.gpu-receipt.json'
    if (Test-Path -LiteralPath $artifact) { throw "Refusing to overwrite existing GPU candidate artifact: $artifact" }
    if (Test-Path -LiteralPath $receipt) { throw "Refusing to overwrite existing GPU receipt: $receipt" }
    $pathParts = $relativeCapture -split '[\\/]'
    $context = if ($pathParts.Count -gt 1) { $pathParts[0] } else { 'default' }
    $caseSpecs.Add([pscustomobject]@{
        context = $context
        groupKey = "$context|$seed|$dimension"
        seed = $seed
        chunkX = [int]$chunkX
        chunkZ = [int]$chunkZ
        dimension = $dimension
        capture = $capture.FullName
        relativeCase = $relativeCase
        artifact = $artifact
    })
}

if ($caseSpecs.Count -eq 0) { throw 'CaseFilter selected no independent NOISE captures; no GPU work was started.' }
$comparisonExpected = $expected
if ($CaseFilter) {
    # Preserve original-only capture bytes and hashes. The comparator receives
    # only the selected set, while the final report explicitly denies full
    # input-corpus coverage. Nothing mutates the authoritative capture tree.
    $comparisonExpected = Join-Path $runs 'selected-oracle-inputs'
    if (Test-Path -LiteralPath $comparisonExpected) { throw "Refusing to reuse selected oracle inputs: $comparisonExpected" }
    New-Item -ItemType Directory -Path $comparisonExpected | Out-Null
    $selectionLines = [System.Collections.Generic.List[string]]::new()
    foreach ($case in $caseSpecs) {
        $relativeCapture = Relative-CapturePath $expected $case.capture
        $selected = Join-Path $comparisonExpected $relativeCapture
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $selected) | Out-Null
        Copy-Item -LiteralPath $case.capture -Destination $selected
        $sourceHash = (Get-FileHash -LiteralPath $case.capture -Algorithm SHA256).Hash
        $copyHash = (Get-FileHash -LiteralPath $selected -Algorithm SHA256).Hash
        if ($sourceHash -ne $copyHash) { throw "Selected oracle copy changed: $selected" }
        $selectionLines.Add("$($case.capture)`t$selected`t$sourceHash")
    }
    [IO.File]::WriteAllLines((Join-Path $runs 'oracle-selection.tsv'), [string[]]$selectionLines,
            [Text.UTF8Encoding]::new($false))
}

foreach ($group in @($caseSpecs | Group-Object groupKey)) {
    $first = $group.Group[0]
    $groupName = ("$($first.context)-seed-$($first.seed)-$($first.dimension)" -replace '[^A-Za-z0-9._-]', '_')
    $runDir = Join-Path $runs $groupName
    if (Test-Path -LiteralPath $runDir) { throw "Refusing to reuse GPU candidate run directory: $runDir" }
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    if ($modFiles.Count -gt 0) {
        $runMods = Join-Path $runDir 'mods'
        New-Item -ItemType Directory -Force -Path $runMods | Out-Null
        $modManifest = [System.Collections.Generic.List[string]]::new()
        foreach ($mod in $modFiles) {
            $destination = Join-Path $runMods $mod.Name
            Copy-Item -LiteralPath $mod.FullName -Destination $destination
            $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            $modManifest.Add("$($mod.Name)`t$($mod.Length)`t$hash")
        }
        [IO.File]::WriteAllLines((Join-Path $runDir 'mod-inputs.tsv'), [string[]]$modManifest,
                [Text.UTF8Encoding]::new($false))
    }
    $manifest = Join-Path $runDir 'cases.tsv'
    Write-CaseManifest $manifest $group.Group
    Write-Host "GPU candidate batch context=$($first.context) seed=$($first.seed) dimension=$($first.dimension) cases=$($group.Count)"
    $gradleArgs = @(
        ':neoforge-1211:gpuCandidateSmoke', '--no-daemon',
        '-Dworldgennext.candidate.capture=true',
        '-Dworldgennext.gpuCandidate.capture=true',
        "-Dworldgennext.gpuCandidate.batchElements=$BatchElements",
        "-Dworldgennext.gpuCandidate.maxShaderSourceChars=$MaxShaderSourceChars",
        "-Dworldgennext.gpuCandidate.enablePipelineOptimization=$($EnablePipelineOptimization.IsPresent.ToString().ToLowerInvariant())",
        '-Dworldgennext.candidate.output=',
        "-Dworldgennext.candidate.caseFile=$(Gradle-ArgsPath $manifest)",
        "-Dworldgennext.candidate.runDir=$(Gradle-ArgsPath $runDir)",
        "-Dworldgennext.candidate.seed=$($first.seed)",
        "-Dworldgennext.candidate.timeoutMillis=$([long]$TimeoutMinutesPerGroup * 60 * 1000)",
        "-Dworldgennext.candidate.dimension=$($first.dimension)"
    )
    $gradleArgs += $frozenGradleArgs
    Assert-FrozenCompiledInputs
    if ($SharedStages) {
        $gradleArgs += @(
            '-Dworldgennext.gpuCandidate.nativeDraft=false',
            '-Dworldgennext.gpuCandidate.sharedSplineShader=true',
            '-Dworldgennext.gpuCandidate.normalNoiseSharedShader=true',
            '-Dworldgennext.gpuCandidate.normalNoiseSharedGenericShader=true',
            '-Dworldgennext.gpuCandidate.blendedNoiseSharedShader=true',
            '-Dworldgennext.gpuCandidate.densityDontInlinePrefix=wg_node_,wg_spline_',
            '-Dworldgennext.gpuCandidate.exactAquiferStage=true',
            '-Dworldgennext.gpuCandidate.exactAquiferBarrierInput=true'
        )
    }
    if ($TraceStages) {
        $gradleArgs += @(
            '-Dworldgennext.gpuCandidate.debugStages=true',
            "-Dworldgennext.gpuCandidate.debugStagesFile=$(Gradle-ArgsPath (Join-Path $runDir 'stages.log'))",
            "-Dworldgennext.gpuCandidate.stagedShaderDir=$(Gradle-ArgsPath (Join-Path $runDir 'shaders'))"
        )
    }
    if ($ResidentNormalNoise) {
        $gradleArgs += '-Dworldgennext.gpuCandidate.sharedNormalNoiseResidentChain=true'
    }
    if ($SharedEndIsland) {
        $gradleArgs += '-Dworldgennext.gpuCandidate.endIslandSharedShader=true'
    }
    Invoke-GpuGroup $gradleArgs $runDir
    foreach ($case in $group.Group) {
        $receipt = $case.artifact + '.gpu-receipt.json'
        if (-not (Test-Path -LiteralPath $case.artifact)) { throw "GPU candidate did not produce $($case.artifact)" }
        if (-not (Test-Path -LiteralPath $receipt)) { throw "GPU candidate did not produce its receipt $receipt" }
        $receiptObject = Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
        if ($receiptObject.status -ne 'PASS' -or $receiptObject.route -ne 'GPU_IEEE_BITS' -or
            $receiptObject.resultAbi -ne 'chunk-result-v4' -or
            $receiptObject.compilerVersion -ne 'worldgennext-gpu-live-v0.2' -or
            $receiptObject.comparedBlocks -le 0 -or $receiptObject.mismatches -ne 0 -or
            [string]::IsNullOrWhiteSpace($receiptObject.shaderHash) -or
            [string]::IsNullOrWhiteSpace($receiptObject.spirvHash) -or
            $null -eq $receiptObject.device) {
            throw "GPU receipt is incomplete or failed: $receipt"
        }
        $results.Add([pscustomobject][ordered]@{
            case = $case.relativeCase.Replace([IO.Path]::DirectorySeparatorChar, '/')
            seed = $case.seed
            chunkX = $case.chunkX
            chunkZ = $case.chunkZ
            dimension = $case.dimension
            expected = $case.capture
            actual = [IO.Path]::GetFullPath($case.artifact)
            receipt = [IO.Path]::GetFullPath($receipt)
            device = $receiptObject.device.name
            shaderHash = $receiptObject.shaderHash
            spirvHash = $receiptObject.spirvHash
            comparedBlocks = [int]$receiptObject.comparedBlocks
            telemetry = $receiptObject.telemetry
            storageTelemetry = $receiptObject.storageTelemetry
            compilationTelemetry = $receiptObject.compilationTelemetry
            spirvCacheTelemetry = $receiptObject.spirvCacheTelemetry
            comparisonExitCode = $null
        })
    }
}

$failureDir = Join-Path $outputs 'comparison-failures'
$dq = [char]34
$compareArgs = "compare-candidate-corpus $dq$(Gradle-ArgsPath $comparisonExpected)$dq $dq$(Gradle-ArgsPath $outputs)$dq $dq--failure-dir=$(Gradle-ArgsPath $failureDir)$dq"
Assert-FrozenCompiledInputs
& $gradle ':oracle-and-replay:run' "--args=$compareArgs" '--no-daemon' @frozenGradleArgs
$compareExit = $LASTEXITCODE
if ($compareExit -ne 0) { throw "GPU candidate corpus comparison failed with exit code $compareExit" }
Assert-FrozenCompiledInputs
foreach ($result in $results) { $result.comparisonExitCode = $compareExit }

$report = [ordered]@{
    schemaVersion = 1
    kind = 'worldgennext_gpu_noise_replay'
    status = 'PASS'
    route = 'GPU_IEEE_BITS'
    sharedStagesRequested = $SharedStages.IsPresent
    sharedEndIslandRequested = $SharedEndIsland.IsPresent
    pipelineOptimizationRequested = $EnablePipelineOptimization.IsPresent
    maxShaderSourceChars = $MaxShaderSourceChars
    residentNormalNoiseRequested = $ResidentNormalNoise.IsPresent
    traceStagesRequested = $TraceStages.IsPresent
    frozenCompiledInputs = $FreezeCompiledInputs.IsPresent
    compiledInputsSha256 = $frozenCompiledInputsHash
    inputCorpusDirectory = $expected
    expectedDirectory = $comparisonExpected
    availableCases = $captures.Count
    caseFilter = $CaseFilter
    selectedSubset = [bool]$CaseFilter
    candidateDirectory = $outputs
    runDirectory = $runs
    batchElements = $BatchElements
    timeoutMinutesPerGroup = $TimeoutMinutesPerGroup
    comparedCases = $results.Count
    comparedFields = $results.Count * 10
    mismatches = 0
    completeCoverage = -not [bool]$CaseFilter
    completeSelectedCoverage = $true
    independentOracle = $true
    cases = $results
}
$reportPath = Join-Path $outputs 'replay-report.json'
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "PASS GPU NOISE replay cases=$($results.Count) report=$reportPath"

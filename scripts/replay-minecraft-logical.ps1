param(
    [ValidateSet('NOISE', 'FULL', 'SAVED')]
    [string]$Endpoint = 'FULL',
    [string]$ExpectedRoot = '',
    [string]$ReopenedExpectedRoot = '',
    [string]$OutputRoot = '',
    [string]$RunRoot = '',
    [string]$ModDirectory = '',
    [int]$BatchSize = 5,
    [ValidateSet('CPU_OWNED', 'GPU_IEEE_BITS')]
    [string]$Backend = 'CPU_OWNED',
    [ValidateRange(1, 180)]
    [int]$TimeoutMinutesPerBatch = 25,
    [ValidateRange(1, 16384)]
    [int]$GpuBatchElements = 16383,
    [switch]$FreezeCompiledInputs,
    [switch]$Reopen
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'Invoke-ReplayProcess.ps1')
. (Join-Path $PSScriptRoot 'CompiledReplayInputs.ps1')
$frozenSnapshot = $null
$frozenGradleArgs = @()
if ($FreezeCompiledInputs) {
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'FreezeCompiledInputs requires PowerShell 7.' }
    $frozenSnapshot = Get-WorldgenCompiledInputSnapshot $repoRoot
    foreach ($module in @('semantic-core','compiler-jvm','compiler-vulkan','material-codec',
            'spatial-data','chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')) {
        $frozenGradleArgs += @('-x', ":${module}:compileJava", '-x', ":${module}:processResources")
    }
    Write-Host "Frozen logical replay compiled inputs SHA256=$($frozenSnapshot.Hash)"
}
function Assert-LogicalCompiledInputs {
    if ($FreezeCompiledInputs) { Assert-WorldgenCompiledInputSnapshot $repoRoot $frozenSnapshot.Hash }
}
if ([string]::IsNullOrWhiteSpace($ExpectedRoot)) {
    throw 'ExpectedRoot must point at an independent original NOISE, FULL or SAVED capture corpus.'
}
if ($BatchSize -le 0 -or $BatchSize -gt 50) { throw 'BatchSize must be between 1 and 50.' }
if ($Reopen -and $Endpoint -ne 'SAVED') {
    throw '-Reopen is only valid for the SAVED logical endpoint.'
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('build\candidate-' + $Endpoint.ToLowerInvariant() + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\run\candidate-' + $Endpoint.ToLowerInvariant() + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$expected = (Resolve-Path -LiteralPath $ExpectedRoot -ErrorAction Stop).Path
$outputs = [IO.Path]::GetFullPath($OutputRoot)
$runs = [IO.Path]::GetFullPath($RunRoot)
if (-not (Test-Path -LiteralPath $expected -PathType Container)) {
    throw "ExpectedRoot is not a directory: $expected"
}

function Test-SameOrChild([string]$path, [string]$parent) {
    $normalizedPath = $path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
function Assert-NonReparse([string]$path, [string]$name) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "$name must not be a reparse point: $path" }
}
function Gradle-Path([string]$path) { return [IO.Path]::GetFullPath($path).Replace('\', '/') }
function Relative-Path([string]$root, [string]$path) {
    $prefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside the expected corpus root: $path"
    }
    return $path.Substring($prefix.Length)
}
function Snapshot-Dimension([string]$path) {
    $line = Get-Content -LiteralPath $path | Where-Object { $_ -like 'value=dimension=*' } | Select-Object -First 1
    if (-not $line) { throw "Capture is missing value=dimension: $path" }
    $value = $line.Substring('value=dimension='.Length).Replace('\\=', '=').Replace('\\\\', '\')
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Capture has a blank dimension: $path" }
    return $value
}
function Write-CaseManifest([string]$path, [object[]]$cases, [string]$outputProperty) {
    $lines = @($cases | ForEach-Object {
        $output = if ($outputProperty -eq 'initial') { $_.initialOutput } else { $_.reopenedOutput }
        "$($_.chunkX)" + [char]9 + "$($_.chunkZ)" + [char]9 + [IO.Path]::GetFullPath($output)
    })
    [IO.File]::WriteAllLines($path, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}
function Assert-Snapshot([string]$path, [string]$name) {
    Assert-NonReparse $path $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$name is missing: $path" }
    if ((Get-Item -LiteralPath $path).Length -le 0) { throw "$name is empty: $path" }
    if ((Get-Content -LiteralPath $path -TotalCount 1) -ne 'TELLURIUM-SNAPSHOT-1') {
        throw "$name has an invalid snapshot header: $path"
    }
}

$roots = @($expected, $outputs, $runs)
if ($ReopenedExpectedRoot) { $roots += [IO.Path]::GetFullPath($ReopenedExpectedRoot) }
for ($i = 0; $i -lt $roots.Count; $i++) {
    for ($j = $i + 1; $j -lt $roots.Count; $j++) {
        if (Test-SameOrChild $roots[$i] $roots[$j] -or Test-SameOrChild $roots[$j] $roots[$i]) {
            throw 'Expected, candidate output, reopened expected and run roots must be distinct.'
        }
    }
}
if ($ReopenedExpectedRoot) {
    $reopenedExpected = (Resolve-Path -LiteralPath $ReopenedExpectedRoot -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $reopenedExpected -PathType Container)) {
        throw "ReopenedExpectedRoot is not a directory: $reopenedExpected"
    }
} else {
    $reopenedExpected = $expected
}
Assert-NonReparse $outputs 'Candidate output root'
Assert-NonReparse $runs 'Candidate run root'
if (Test-Path -LiteralPath $outputs) { throw "Refusing to reuse candidate output root: $outputs" }
if (Test-Path -LiteralPath $runs) { throw "Refusing to reuse candidate run root: $runs" }
New-Item -ItemType Directory -Force -Path $outputs, $runs | Out-Null
if ($FreezeCompiledInputs) {
    [IO.File]::WriteAllText((Join-Path $runs 'frozen-compiled-inputs.tsv'),
            $frozenSnapshot.Manifest, [Text.UTF8Encoding]::new($false))
}
$initialOutputs = Join-Path $outputs 'initial'
$reopenedOutputs = Join-Path $outputs 'reopened'
New-Item -ItemType Directory -Force -Path $initialOutputs | Out-Null
if ($Reopen) { New-Item -ItemType Directory -Force -Path $reopenedOutputs | Out-Null }

$modFiles = @()
if (-not [string]::IsNullOrWhiteSpace($ModDirectory)) {
    $modRoot = (Resolve-Path -LiteralPath $ModDirectory -ErrorAction Stop).Path
    if (-not (Get-Item -LiteralPath $modRoot).PSIsContainer) { throw "ModDirectory is not a directory: $modRoot" }
    $modFiles = @(Get-ChildItem -LiteralPath $modRoot -Filter '*.jar' -File | Sort-Object Name)
    if ($modFiles.Count -eq 0) { throw "ModDirectory contains no jar files: $modRoot" }
}

$captures = @(Get-ChildItem -LiteralPath $expected -Filter '*.snap' -File -Recurse | Sort-Object FullName)
if ($captures.Count -eq 0) { throw "No independent oracle captures found in $expected" }
$caseSpecs = [System.Collections.Generic.List[object]]::new()
foreach ($capture in $captures) {
    if ($capture.BaseName -notmatch '^seed-(?<seed>-?\d+)-x-(?<x>-?\d+)-z-(?<z>-?\d+)-(?<endpoint>NOISE|FULL|SAVED)$') {
        throw "Capture filename is not a signed $Endpoint case key: $($capture.Name)"
    }
    if ($Matches.endpoint -ne $Endpoint) { throw "ExpectedRoot mixes endpoints or does not match -Endpoint ${Endpoint}: $($capture.Name)" }
    $relative = Relative-Path $expected $capture.FullName
    $relativeCase = $relative -replace '\.snap$', ''
    $pathParts = $relative -split '[\\/]'
    $context = if ($pathParts.Count -gt 1) { $pathParts[0] } else { 'default' }
    $caseSpecs.Add([pscustomobject]@{
        context = $context
        groupKey = "$context|$($Matches.seed)|$(Snapshot-Dimension $capture.FullName)"
        seed = $Matches.seed
        chunkX = [int]$Matches.x
        chunkZ = [int]$Matches.z
        dimension = Snapshot-Dimension $capture.FullName
        capture = $capture.FullName
        expectedSha256 = (Get-FileHash -LiteralPath $capture.FullName -Algorithm SHA256).Hash
        reopenedExpected = if ($Reopen) { Join-Path $reopenedExpected $relative } else { $null }
        reopenedExpectedSha256 = if ($Reopen) {
            (Get-FileHash -LiteralPath (Join-Path $reopenedExpected $relative) -Algorithm SHA256).Hash
        } else { $null }
        liveNoiseEvidence = $null
        liveBatchRunRoot = $null
        initialCandidateSha256 = $null
        reopenedCandidateSha256 = $null
        noiseArtifactSha256 = $null
        noiseStatusSha256 = $null
        backendReceiptSha256 = $null
        publicationReceiptSha256 = $null
        batchPropertiesSha256 = $null
        relativeCase = $relativeCase
        initialOutput = Join-Path $initialOutputs ($relativeCase + '.snap')
        reopenedOutput = Join-Path $reopenedOutputs ($relativeCase + '.snap')
    })
}

$gradle = Join-Path $repoRoot 'gradlew.bat'
function Copy-ModInputs([string]$runDir) {
    if ($modFiles.Count -eq 0) { return }
    $runMods = Join-Path $runDir 'mods'
    if (Test-Path -LiteralPath $runMods -PathType Container) {
        foreach ($mod in $modFiles) {
            $destination = Join-Path $runMods $mod.Name
            if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
                throw "Existing run mod input is incomplete: $destination"
            }
            $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            $sourceHash = (Get-FileHash -LiteralPath $mod.FullName -Algorithm SHA256).Hash
            if ($hash -ne $sourceHash) { throw "Existing run mod input differs from source: $destination" }
        }
        return
    }
    New-Item -ItemType Directory -Force -Path $runMods | Out-Null
    $manifest = [System.Collections.Generic.List[string]]::new()
    foreach ($mod in $modFiles) {
        $destination = Join-Path $runMods $mod.Name
        if (Test-Path -LiteralPath $destination) { throw "Refusing to overwrite run mod input: $destination" }
        Copy-Item -LiteralPath $mod.FullName -Destination $destination
        $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
        $manifest.Add("$($mod.Name)`t$($mod.Length)`t$hash")
    }
    [IO.File]::WriteAllLines((Join-Path $runDir 'mod-inputs.tsv'), [string[]]$manifest,
            [Text.UTF8Encoding]::new($false))
}
function Invoke-LogicalBatch([object[]]$batch, [string]$runDir, [bool]$requireLiveNoise, [string]$outputProperty) {
    Assert-LogicalCompiledInputs
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    Copy-ModInputs $runDir
    $liveNoiseEvidence = Join-Path $runDir 'live-noise-results'
    $manifest = Join-Path $runDir ('cases-' + $outputProperty + '.tsv')
    Write-CaseManifest $manifest $batch $outputProperty
    $first = $batch[0]
    $required = $requireLiveNoise.ToString().ToLowerInvariant()
    $args = @(
        ':neoforge-1211:candidateLogicalSmoke', '--no-daemon', '--console=plain',
        '-Dtellurium.candidate.capture=true',
        "-Dtellurium.candidate.endpoint=$Endpoint",
        '-Dtellurium.candidate.live=true',
        "-Dtellurium.candidate.logicalBackend=$Backend",
        "-Dtellurium.candidate.timeoutMillis=$([long]$TimeoutMinutesPerBatch * 60 * 1000)",
        "-Dtellurium.candidate.requireLiveNoise=$required",
        '-Dtellurium.candidate.output=',
        "-Dtellurium.candidate.caseFile=$(Gradle-Path $manifest)",
        "-Dtellurium.candidate.runDir=$(Gradle-Path $runDir)",
        "-Dtellurium.candidate.liveNoiseResultDir=$(Gradle-Path $liveNoiseEvidence)",
        "-Dtellurium.candidate.seed=$($first.seed)",
        "-Dtellurium.candidate.dimension=$($first.dimension)"
    )
    if ($Backend -eq 'GPU_IEEE_BITS') {
        $args += @(
            '-Dtellurium.gpuCandidate.nativeDraft=false',
            "-Dtellurium.gpuCandidate.batchElements=$GpuBatchElements",
            '-Dtellurium.gpuCandidate.maxShaderSourceChars=1500000',
            '-Dtellurium.gpuCandidate.sharedSplineShader=true',
            '-Dtellurium.gpuCandidate.normalNoiseSharedShader=true',
            '-Dtellurium.gpuCandidate.normalNoiseSharedGenericShader=true',
            '-Dtellurium.gpuCandidate.blendedNoiseSharedShader=true',
            '-Dtellurium.gpuCandidate.endIslandSharedShader=true',
            '-Dtellurium.gpuCandidate.densityDontInlinePrefix=wg_node_,wg_spline_',
            '-Dtellurium.gpuCandidate.exactAquiferStage=true',
            '-Dtellurium.gpuCandidate.exactAquiferBarrierInput=true'
        )
    }
    Invoke-ReplayProcess -Command $gradle -Arguments ($args + $frozenGradleArgs) -WorkingDirectory $repoRoot `
        -LogDirectory (Join-Path $runDir $outputProperty) -TimeoutSeconds ($TimeoutMinutesPerBatch * 60)
    Assert-LogicalCompiledInputs
    foreach ($case in $batch) {
        $output = if ($outputProperty -eq 'initial') { $case.initialOutput } else { $case.reopenedOutput }
        Assert-Snapshot $output "Candidate $Endpoint output"
        $status = $output + '.candidate-status'
        if (-not (Test-Path -LiteralPath $status -PathType Leaf) -or
            (Get-Content -LiteralPath $status -TotalCount 1) -ne 'PASS') {
            throw "Candidate $Endpoint did not produce a PASS status: $status"
        }
        if ($requireLiveNoise) {
            $stem = Join-Path $liveNoiseEvidence ("chunk-$($case.chunkX)-$($case.chunkZ)")
            $case.liveNoiseEvidence = $stem
            $case.liveBatchRunRoot = $runDir
            $receiptSuffix = if ($Backend -eq 'GPU_IEEE_BITS') { '.gpu-receipt.json' } else { '.cpu-receipt.json' }
            foreach ($evidence in @("${stem}.chunk", ($stem + $receiptSuffix), "${stem}.candidate-status")) {
                if (-not (Test-Path -LiteralPath $evidence -PathType Leaf) -or
                        (Get-Item -LiteralPath $evidence).Length -le 0) {
                    throw "Candidate $Endpoint did not produce live NOISE evidence: $evidence"
                }
            }
            $liveStatus = @(Get-Content -LiteralPath ($stem + '.candidate-status') -TotalCount 2)
            if ($liveStatus.Count -ne 2 -or $liveStatus[0] -ne 'PASS' -or $liveStatus[1] -ne 'COMMITTED') {
                throw "Candidate $Endpoint live NOISE evidence is not committed: $($stem + '.candidate-status')"
            }
            if ($Backend -eq 'GPU_IEEE_BITS') {
                $receipt = Get-Content -LiteralPath ($stem + '.gpu-receipt.json') -Raw | ConvertFrom-Json
                $commit = Get-Content -LiteralPath ($stem + '.gpu-commit.json') -Raw | ConvertFrom-Json
                if ($receipt.kind -ne 'tellurium_gpu_live_receipt' -or $receipt.status -ne 'BACKEND_VALIDATED' -or
                    $receipt.route -ne $Backend -or $receipt.resultAbi -ne 'chunk-result-v4' -or
                    $receipt.compilerVersion -ne 'tellurium-gpu-live-v0.2' -or
                    $receipt.submitted -le 0 -or $receipt.completed -ne $receipt.submitted -or
                    $receipt.validated -ne $receipt.completed -or $receipt.committed -ne 0 -or
                    $receipt.logicalGpuElements -le 0 -or $receipt.logicalGpuElements -gt $receipt.storageBlocks -or
                    $receipt.storageBlocks -ne $receipt.submitted -or $null -eq $receipt.device -or
                    $receipt.shaderHash -notmatch '^[0-9a-fA-F]{64}$' -or
                    $receipt.spirvHash -notmatch '^[0-9a-fA-F]{64}$' -or
                    [string]::IsNullOrWhiteSpace($receipt.executionId) -or
                    $receipt.chunkX -ne $case.chunkX -or $receipt.chunkZ -ne $case.chunkZ -or
                    $commit.kind -ne 'tellurium_gpu_live_commit' -or $commit.status -ne 'COMMITTED' -or
                    $commit.route -ne $Backend -or $commit.committed -ne $receipt.validated -or
                    $commit.executionId -ne $receipt.executionId -or $commit.contextKey -ne $receipt.contextKey -or
                    $commit.programHash -ne $receipt.programHash -or $commit.snapshotHash -ne $receipt.snapshotHash -or
                    $commit.chunkX -ne $case.chunkX -or $commit.chunkZ -ne $case.chunkZ -or
                    $commit.worldEpoch -ne $receipt.worldEpoch -or $commit.deviceGeneration -ne $receipt.deviceGeneration) {
                    throw "Incomplete or unlinked live GPU execution/commit evidence: $stem"
                }
            }
        }
    }
}

foreach ($group in @($caseSpecs | Group-Object groupKey)) {
    $groupCases = @($group.Group)
    for ($start = 0; $start -lt $groupCases.Count; $start += $BatchSize) {
        $batch = @($groupCases | Select-Object -Skip $start -First $BatchSize)
        $first = $batch[0]
        $batchNumber = [int]($start / $BatchSize)
        $name = ("$($first.context)-seed-$($first.seed)-$($first.dimension)-batch-$('{0:D2}' -f $batchNumber)" -replace '[^A-Za-z0-9._-]', '_')
        $runDir = Join-Path $runs $name
        Write-Host "Candidate $Endpoint batch context=$($first.context) seed=$($first.seed) dimension=$($first.dimension) batch=$batchNumber cases=$($batch.Count)"
        Invoke-LogicalBatch $batch $runDir $true 'initial'
        if ($Reopen) {
            Write-Host "Reopening candidate SAVED batch context=$($first.context) seed=$($first.seed) batch=$batchNumber"
            Invoke-LogicalBatch $batch $runDir $false 'reopened'
        }
    }
}

function Compare-Corpus([string]$actualRoot, [string]$expectedRootForPass, [string]$failureRoot, [string]$label) {
    Assert-LogicalCompiledInputs
    $dq = [char]34
    $args = "compare-corpus $dq$(Gradle-Path $expectedRootForPass)$dq $dq$(Gradle-Path $actualRoot)$dq --failure-dir=$(Gradle-Path $failureRoot)"
    Invoke-ReplayProcess -Command $gradle -Arguments (@(':oracle-and-replay:run', "--args=$args", '--no-daemon', '--console=plain') + $frozenGradleArgs) `
        -WorkingDirectory $repoRoot -LogDirectory $failureRoot -TimeoutSeconds ($TimeoutMinutesPerBatch * 60)
    Assert-LogicalCompiledInputs
}
foreach ($case in $caseSpecs) {
    $case.initialCandidateSha256 = (Get-FileHash -LiteralPath $case.initialOutput -Algorithm SHA256).Hash
    $case.noiseArtifactSha256 = (Get-FileHash -LiteralPath ($case.liveNoiseEvidence + '.chunk') -Algorithm SHA256).Hash
    $case.noiseStatusSha256 = (Get-FileHash -LiteralPath ($case.liveNoiseEvidence + '.candidate-status') -Algorithm SHA256).Hash
    $case.backendReceiptSha256 = (Get-FileHash -LiteralPath ($case.liveNoiseEvidence + $(if ($Backend -eq 'GPU_IEEE_BITS') { '.gpu-receipt.json' } else { '.cpu-receipt.json' })) -Algorithm SHA256).Hash
    $case.batchPropertiesSha256 = (Get-FileHash -LiteralPath (Join-Path $case.liveBatchRunRoot 'server.properties') -Algorithm SHA256).Hash
    if ($Backend -eq 'GPU_IEEE_BITS') { $case.publicationReceiptSha256 = (Get-FileHash -LiteralPath ($case.liveNoiseEvidence + '.gpu-commit.json') -Algorithm SHA256).Hash }
    if ($Reopen) { $case.reopenedCandidateSha256 = (Get-FileHash -LiteralPath $case.reopenedOutput -Algorithm SHA256).Hash }
}
Compare-Corpus $initialOutputs $expected (Join-Path $outputs 'failures-initial') "Candidate $Endpoint"
if ($Reopen) {
    Compare-Corpus $reopenedOutputs $reopenedExpected (Join-Path $outputs 'failures-reopened') 'Candidate SAVED reopen'
}

$report = [ordered]@{
    schemaVersion = 1
    kind = 'tellurium_candidate_logical_endpoint_replay'
    status = if ($Reopen) { 'PASS_CANDIDATE_VERIFICATION_ONLY' } else { 'PASS_CANDIDATE_ENDPOINT_ONLY' }
    endpoint = $Endpoint
    route = $Backend
    coordinatorDispatch = 'INLINE_REFERENCE'
    releaseQualification = $false
    productionHookEnabled = $false
    frozenCompiledInputs = $FreezeCompiledInputs.IsPresent
    compiledInputsSha256 = if ($frozenSnapshot) { $frozenSnapshot.Hash } else { $null }
    compiledInputFiles = if ($frozenSnapshot) { $frozenSnapshot.FileCount } else { $null }
    timeoutMinutesPerBatch = $TimeoutMinutesPerBatch
    gpuBatchElements = if ($Backend -eq 'GPU_IEEE_BITS') { $GpuBatchElements } else { $null }
    caseCount = $caseSpecs.Count
    comparedFields = $caseSpecs.Count * 10
    comparedCases = $caseSpecs.Count
    independentComparison = 'PASS'
    reopenedComparedCases = if ($Reopen) { $caseSpecs.Count } else { 0 }
    reopenedComparedFields = if ($Reopen) { $caseSpecs.Count * 10 } else { 0 }
    initialExpectedRoot = $expected
    initialCandidateRoot = $initialOutputs
    reopenedExpectedRoot = if ($Reopen) { $reopenedExpected } else { $null }
    reopenedCandidateRoot = if ($Reopen) { $reopenedOutputs } else { $null }
    runRoot = $runs
    reopenVerified = [bool]$Reopen
    cases = @($caseSpecs | ForEach-Object {
        if ((Get-FileHash -LiteralPath $_.capture -Algorithm SHA256).Hash -ne $_.expectedSha256) {
            throw "Original input changed during logical replay: $($_.capture)"
        }
        if ($Reopen -and (Get-FileHash -LiteralPath $_.reopenedExpected -Algorithm SHA256).Hash -ne $_.reopenedExpectedSha256) {
            throw "Reopened original input changed during logical replay: $($_.reopenedExpected)"
        }
        if ((Get-FileHash -LiteralPath $_.initialOutput -Algorithm SHA256).Hash -ne $_.initialCandidateSha256 -or
                (Get-FileHash -LiteralPath ($_.liveNoiseEvidence + '.chunk') -Algorithm SHA256).Hash -ne $_.noiseArtifactSha256 -or
                ($Reopen -and (Get-FileHash -LiteralPath $_.reopenedOutput -Algorithm SHA256).Hash -ne $_.reopenedCandidateSha256)) {
            throw 'Candidate/evidence bytes changed across the independent comparison.'
        }
        $backendSuffix = if ($Backend -eq 'GPU_IEEE_BITS') { '.gpu-receipt.json' } else { '.cpu-receipt.json' }
        if ((Get-FileHash -LiteralPath ($_.liveNoiseEvidence + '.candidate-status') -Algorithm SHA256).Hash -ne $_.noiseStatusSha256 -or
                (Get-FileHash -LiteralPath ($_.liveNoiseEvidence + $backendSuffix) -Algorithm SHA256).Hash -ne $_.backendReceiptSha256 -or
                (Get-FileHash -LiteralPath (Join-Path $_.liveBatchRunRoot 'server.properties') -Algorithm SHA256).Hash -ne $_.batchPropertiesSha256 -or
                ($Backend -eq 'GPU_IEEE_BITS' -and (Get-FileHash -LiteralPath ($_.liveNoiseEvidence + '.gpu-commit.json') -Algorithm SHA256).Hash -ne $_.publicationReceiptSha256)) {
            throw 'Execution/publication/seed evidence changed across the independent comparison.'
        }
        [ordered]@{
            case = $_.relativeCase.Replace('\','/')
            seed = $_.seed
            chunkX = $_.chunkX
            chunkZ = $_.chunkZ
            dimension = $_.dimension
            initialExpected = $_.capture
            initialExpectedSha256 = $_.expectedSha256
            initialCandidate = $_.initialOutput
            initialCandidateSha256 = $_.initialCandidateSha256
            liveBatchRunRoot = $_.liveBatchRunRoot
            batchPropertiesSha256 = $_.batchPropertiesSha256
            noiseArtifact = $_.liveNoiseEvidence + '.chunk'
            noiseArtifactSha256 = $_.noiseArtifactSha256
            noiseStatus = $_.liveNoiseEvidence + '.candidate-status'
            noiseStatusSha256 = $_.noiseStatusSha256
            backendReceipt = $_.liveNoiseEvidence + $(if ($Backend -eq 'GPU_IEEE_BITS') { '.gpu-receipt.json' } else { '.cpu-receipt.json' })
            backendReceiptSha256 = $_.backendReceiptSha256
            publicationReceipt = if ($Backend -eq 'GPU_IEEE_BITS') { $_.liveNoiseEvidence + '.gpu-commit.json' } else { $null }
            publicationReceiptSha256 = $_.publicationReceiptSha256
            reopenedExpected = $_.reopenedExpected
            reopenedExpectedSha256 = $_.reopenedExpectedSha256
            reopenedCandidate = if ($Reopen) { $_.reopenedOutput } else { $null }
            reopenedCandidateSha256 = $_.reopenedCandidateSha256
        }
    })
    note = if ($Reopen) {
        'Candidate logical SAVED endpoint compared against an independent original corpus and reopened in a fresh process. Selected route and actual per-core commit evidence are separate from unqualified halo work; this is not production or release qualification.'
    } elseif ($Endpoint -eq 'NOISE') {
        'Candidate logical NOISE used the selected isolated backend and real commit path, then compared the published logical chunk against independent original captures. This is live verification only, not production or release qualification.'
    } else {
        'Candidate logical FULL compared against independent original captures after the selected isolated backend and original downstream stages. This is not production or release qualification.'
    }
}
$reportPath = Join-Path $outputs 'logical-endpoint-report.json'
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "PASS candidate $Endpoint endpoint cases=$($caseSpecs.Count) report=$reportPath"

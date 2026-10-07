param(
    [string]$ExpectedRoot = (Join-Path $PSScriptRoot '..\build\oracle-captures\original'),
    [string]$OutputRoot = '',
    [string]$RunRoot = '',
    [string]$ModDirectory = '',
    [int]$BatchSize = 5,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$expected = (Resolve-Path $ExpectedRoot -ErrorAction Stop).Path
if ($BatchSize -le 0 -or $BatchSize -gt 50) { throw 'BatchSize must be between 1 and 50.' }

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot ('build\cpu-candidates\' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\run\cpu-candidates-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$outputs = [IO.Path]::GetFullPath($OutputRoot)
$runs = [IO.Path]::GetFullPath($RunRoot)
function Test-SameOrChild([string]$path, [string]$parent) {
    $normalizedPath = $path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
if (Test-SameOrChild $outputs $expected -or Test-SameOrChild $runs $expected) {
    throw 'Candidate output/run roots must not be the independent oracle directory or a child of it.'
}
if (Test-SameOrChild $outputs $runs -or Test-SameOrChild $runs $outputs) {
    throw 'Candidate output and run roots must be distinct.'
}
function Assert-NonReparse([string]$path, [string]$name) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "$name must not be a reparse point: $path"
    }
}
function Assert-RegularTarget([string]$path, [string]$name) {
    Assert-NonReparse $path $name
    if (-not (Test-Path -LiteralPath $path)) { return }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.PSIsContainer) { throw "$name must be a regular file, not a directory: $path" }
}
Assert-NonReparse $outputs 'Candidate output root'
Assert-NonReparse $runs 'Candidate run root'
$modFiles = @()
if (-not [string]::IsNullOrWhiteSpace($ModDirectory)) {
    $modRoot = (Resolve-Path -LiteralPath $ModDirectory -ErrorAction Stop).Path
    if (-not (Get-Item -LiteralPath $modRoot).PSIsContainer) { throw "ModDirectory is not a directory: $modRoot" }
    $modFiles = @(Get-ChildItem -LiteralPath $modRoot -Filter '*.jar' -File | Sort-Object Name)
    if ($modFiles.Count -eq 0) { throw "ModDirectory contains no jar files: $modRoot" }
}
if (-not $Resume) {
    New-Item -ItemType Directory -Force -Path $outputs, $runs | Out-Null
} else {
    if (-not (Test-Path -LiteralPath $outputs -PathType Container)) {
        throw "-Resume requires an existing candidate output root: $outputs"
    }
    New-Item -ItemType Directory -Force -Path $runs | Out-Null
}

$captures = @(Get-ChildItem -LiteralPath $expected -Filter '*.snap' -File -Recurse | Sort-Object FullName)
if ($captures.Count -eq 0) { throw "No independent oracle captures found in $expected" }
$gradle = Join-Path $repoRoot 'gradlew.bat'
$results = [System.Collections.Generic.List[object]]::new()
$caseSpecs = [System.Collections.Generic.List[object]]::new()

function Gradle-ArgsPath([string]$path) {
    return $path.Replace('\', '/')
}
function Write-CaseManifest([string]$path, [object[]]$cases) {
    $lines = @($cases | ForEach-Object { "$($_.chunkX)" + [char]9 + "$($_.chunkZ)" + [char]9 + [IO.Path]::GetFullPath($_.artifact) })
    [IO.File]::WriteAllLines($path, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}
function Write-Checkpoint([string]$path, [object[]]$cases, [string]$expectedDirectory,
                          [string]$candidateDirectory, [string]$runDirectory, [int]$batchSize) {
    Assert-RegularTarget $path 'Replay checkpoint'
    $completed = @($cases | Where-Object { $_.completed }).Count
    $checkpoint = [ordered]@{
        schemaVersion = 1
        kind = 'tellurium_cpu_noise_replay_checkpoint'
        expectedDirectory = $expectedDirectory
        candidateDirectory = $candidateDirectory
        runDirectory = $runDirectory
        batchSize = $batchSize
        resumed = [bool]$Resume
        caseCount = $cases.Count
        completedCases = $completed
        pendingCases = $cases.Count - $completed
        cases = @($cases | ForEach-Object {
            [ordered]@{
                case = $_.relativeCase.Replace([IO.Path]::DirectorySeparatorChar, '/')
                seed = $_.seed
                chunkX = $_.chunkX
                chunkZ = $_.chunkZ
                dimension = $_.dimension
                artifact = [IO.Path]::GetFullPath($_.artifact)
                completed = [bool]$_.completed
            }
        })
    }
    $temporary = Join-Path ([IO.Path]::GetDirectoryName($path)) ('.' + [IO.Path]::GetFileName($path) + '.tmp')
    Assert-RegularTarget $temporary 'Replay checkpoint temporary file'
    $checkpoint | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $path -Force
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
    $dimension = Snapshot-Dimension $capture.FullName
    $relativeCapture = Relative-CapturePath $expected $capture.FullName
    $relativeCase = $relativeCapture -replace '\.snap$', ''
    $artifact = Join-Path $outputs ($relativeCase + '.chunk')
    $completed = $false
    $statusPath = $artifact + '.candidate-status'
    $statusPresent = Test-Path -LiteralPath $statusPath
    if ($statusPresent) {
        Assert-RegularTarget $statusPath 'Candidate status'
        $statusLine = Get-Content -LiteralPath $statusPath -TotalCount 1
        if ($statusLine -ne 'PASS') {
            throw "Existing candidate status is not PASS: $statusPath"
        }
        if (-not $Resume -and -not (Test-Path -LiteralPath $artifact)) {
            throw "Refusing to reuse an existing candidate status without its artifact: $statusPath"
        }
    }
    if (Test-Path -LiteralPath $artifact) {
        Assert-RegularTarget $artifact 'Candidate artifact'
        $artifactItem = Get-Item -LiteralPath $artifact -Force
        if ($artifactItem.Length -le 0) {
            throw "Existing candidate artifact is not a non-empty file: $artifact"
        }
        if (-not $Resume) { throw "Refusing to overwrite existing candidate artifact: $artifact" }
        # Candidate results are published with CREATE_NEW plus an atomic move.
        # A present non-empty artifact is therefore resumable even when Ctrl+C
        # arrived before the temporary draft status marker was written.
        $completed = $true
    } elseif ($statusPresent) {
        throw "Existing candidate status has no candidate artifact: $statusPath"
    }
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
        completed = $completed
    })
}

$checkpointPath = Join-Path $outputs 'replay-checkpoint.json'
Write-Checkpoint $checkpointPath $caseSpecs $expected $outputs $runs $BatchSize

foreach ($group in @($caseSpecs | Group-Object groupKey)) {
    $groupCases = @($group.Group | Where-Object { -not $_.completed })
    if ($groupCases.Count -eq 0) { continue }
    for ($batchStart = 0; $batchStart -lt $groupCases.Count; $batchStart += $BatchSize) {
        $batchCases = @($groupCases | Select-Object -Skip $batchStart -First $BatchSize)
        $first = $batchCases[0]
        $batchNumber = [int]($batchStart / $BatchSize)
        $groupName = (("$($first.context)-seed-$($first.seed)-$($first.dimension)-batch-$('{0:D2}' -f $batchNumber)" -replace '[^A-Za-z0-9._-]', '_'))
        $runDir = Join-Path $runs $groupName
        if (Test-Path -LiteralPath $runDir) {
            if (-not $Resume) { throw "Refusing to reuse candidate run directory: $runDir" }
            $suffix = 1
            do {
                $runDir = Join-Path $runs ($groupName + '-resume-' + ('{0:D2}' -f $suffix))
                $suffix++
            } while (Test-Path -LiteralPath $runDir)
        }
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
        Write-CaseManifest $manifest $batchCases
        Write-Host "CPU candidate batch context=$($first.context) seed=$($first.seed) dimension=$($first.dimension) batch=$batchNumber cases=$($batchCases.Count)"
        $gradleArgs = @(
            ':neoforge-1211:candidateSmoke', '--no-daemon',
            '-Dtellurium.candidate.capture=true',
            '-Dtellurium.candidate.output=',
            "-Dtellurium.candidate.caseFile=$(Gradle-ArgsPath $manifest)",
            "-Dtellurium.candidate.runDir=$(Gradle-ArgsPath $runDir)",
            "-Dtellurium.candidate.seed=$($first.seed)",
            "-Dtellurium.candidate.dimension=$($first.dimension)",
            "-Dtellurium.candidate.cpuWorkers=$(if ($env:TELLURIUM_CANDIDATE_CPU_WORKERS) { $env:TELLURIUM_CANDIDATE_CPU_WORKERS } else { '2' })"
        )
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Candidate capture batch failed for seed $($first.seed) dimension $($first.dimension) batch $batchNumber" }
        foreach ($case in $batchCases) {
            if (-not (Test-Path -LiteralPath $case.artifact)) { throw "Candidate capture did not produce $($case.artifact)" }
            $case.completed = $true
        }
        Write-Checkpoint $checkpointPath $caseSpecs $expected $outputs $runs $BatchSize
    }
}

foreach ($case in $caseSpecs) {
    $results.Add([pscustomobject][ordered]@{
        case = $case.relativeCase.Replace([IO.Path]::DirectorySeparatorChar, '/')
        seed = $case.seed
        chunkX = $case.chunkX
        chunkZ = $case.chunkZ
        dimension = $case.dimension
        expected = $case.capture
        actual = [IO.Path]::GetFullPath($case.artifact)
        comparisonExitCode = $null
    })
}

$failureDir = Join-Path $outputs 'comparison-failures'
$dq = [char]34
$compareArgs = "compare-candidate-corpus $dq$(Gradle-ArgsPath $expected)$dq $dq$(Gradle-ArgsPath $outputs)$dq $dq--failure-dir=$(Gradle-ArgsPath $failureDir)$dq"
& $gradle ':oracle-and-replay:run' "--args=$compareArgs" '--no-daemon'
$compareExit = $LASTEXITCODE
if ($compareExit -ne 0) { throw "CPU candidate corpus comparison failed with exit code $compareExit" }
foreach ($result in $results) { $result.comparisonExitCode = $compareExit }

$report = [ordered]@{
    schemaVersion = 1
    kind = 'tellurium_cpu_noise_replay'
    status = 'PASS'
    expectedDirectory = $expected
    candidateDirectory = $outputs
    runDirectory = $runs
    comparedCases = $results.Count
    comparedFields = $results.Count * 10
    mismatches = 0
    completeCoverage = $true
    independentOracle = $true
    cases = $results
}
$reportPath = Join-Path $outputs 'replay-report.json'
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Checkpoint $checkpointPath $caseSpecs $expected $outputs $runs $BatchSize
Write-Host "PASS CPU NOISE replay cases=$($results.Count) report=$reportPath"

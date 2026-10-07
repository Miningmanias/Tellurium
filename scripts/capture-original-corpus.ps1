param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
    [ValidateSet('NOISE', 'FULL', 'SAVED')]
    [string]$Endpoint = 'NOISE',
    [string]$Dimension = 'minecraft:overworld',
    [string]$ContextId = '',
    [string]$StackFingerprint = 'minecraft-1.21.1-neoforge-21.1.176',
    [ValidateRange(1, 25)]
    [int]$CoreSquareSideChunks = 1,
    [string[]]$Seeds = @('0', '12345', '-1', '9223372036854775807', '-9223372036854775808'),
    [ValidateRange(1, 180)]
    [int]$TimeoutMinutesPerSeed = 25,
    [string]$ModDirectory = '',
    [string]$CaptureRoot = '',
    [string]$ReopenedCaptureRoot = '',
    [string]$RunRoot = '',
    [switch]$FreezeCompiledInputs,
    [switch]$Reopen,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'Invoke-ReplayProcess.ps1')
. (Join-Path $PSScriptRoot 'CompiledReplayInputs.ps1')
. (Join-Path $PSScriptRoot 'LogicalEndpointMatrix.ps1')
$frozen = $null
$frozenGradleArgs = @()
$captureRoot = if ($CaptureRoot) { [IO.Path]::GetFullPath($CaptureRoot) } else { Join-Path $root 'build\oracle-captures\original' }
$runRoot = if ($RunRoot) { [IO.Path]::GetFullPath($RunRoot) } else { Join-Path $root ('build\oracle-runs\original-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')) }
$Endpoint=$Endpoint.ToUpperInvariant()
if ($Reopen -and $Endpoint -ne 'SAVED') { throw '-Reopen requires the SAVED endpoint.' }
if ($Reopen -and -not $ReopenedCaptureRoot) { throw '-Reopen requires a separate ReopenedCaptureRoot.' }
if ($ReopenedCaptureRoot -and -not $Reopen) { throw 'ReopenedCaptureRoot requires -Reopen.' }
$reopenedRoot = if ($Reopen) { [IO.Path]::GetFullPath($ReopenedCaptureRoot) } else { '' }
function Test-SameOrChild([string]$path, [string]$parent) {
    $normalizedPath = $path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $normalizedParent = $parent.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $normalizedPath.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
function Gradle-ArgsPath([string]$path) { return $path.Replace('\', '/') }
function Write-CaseManifest([string]$path, [object[]]$cases) {
    $lines = @($cases | ForEach-Object { "$($_.chunkX)`t$($_.chunkZ)`t$([IO.Path]::GetFullPath($_.output))" })
    [IO.File]::WriteAllLines($path, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}
if (Test-SameOrChild $captureRoot $runRoot -or Test-SameOrChild $runRoot $captureRoot) {
    throw 'Oracle capture and run roots must be distinct.'
}
if ($Reopen) {
    foreach ($other in @($captureRoot,$runRoot)) {
        if (Test-SameOrChild $reopenedRoot $other -or Test-SameOrChild $other $reopenedRoot) { throw 'Reopened, original and run roots must be distinct.' }
    }
}
foreach ($path in @($captureRoot,$runRoot,$reopenedRoot) | Where-Object { $_ }) { Assert-LogicalMatrixPlainPath $path }
if ([string]::IsNullOrWhiteSpace($Dimension)) { throw 'Dimension must not be blank' }
if ([string]::IsNullOrWhiteSpace($StackFingerprint)) { throw 'StackFingerprint must not be blank' }
if ($CoreSquareSideChunks -le 0 -or $CoreSquareSideChunks % 2 -eq 0) {
    throw 'CoreSquareSideChunks must be a positive odd number.'
}
$modFiles = @()
if (-not [string]::IsNullOrWhiteSpace($ModDirectory)) {
    $modRoot = (Resolve-Path -LiteralPath $ModDirectory -ErrorAction Stop).Path
    Assert-LogicalMatrixPlainPath $modRoot
    foreach ($destinationRoot in @($captureRoot,$runRoot,$reopenedRoot) | Where-Object { $_ }) {
        if (Test-LogicalMatrixOverlap $modRoot $destinationRoot) { throw 'Mod inputs and capture/run roots must not overlap.' }
    }
    if (-not (Get-Item -LiteralPath $modRoot).PSIsContainer) { throw "ModDirectory is not a directory: $modRoot" }
    $modFiles = @(Get-ChildItem -LiteralPath $modRoot -Filter '*.jar' -File | Sort-Object Name)
    if ($modFiles.Count -eq 0) { throw "ModDirectory contains no jar files: $modRoot" }
    foreach ($mod in $modFiles) { Assert-LogicalMatrixPlainPath $mod.FullName }
}
$contextDirectoryName = if ([string]::IsNullOrWhiteSpace($ContextId)) { $Dimension } else { $ContextId }
$contextDirectoryName = $contextDirectoryName.Trim() -replace '[^A-Za-z0-9._-]', '_'
if ([string]::IsNullOrWhiteSpace($contextDirectoryName) -or $contextDirectoryName -in @('.', '..')) {
    throw 'ContextId must resolve to a non-traversal directory name'
}
if ($null -eq $Seeds -or $Seeds.Count -eq 0) { throw 'Seeds must not be empty.' }
$selectedSeeds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($seedText in $Seeds) {
    $parsedSeed = 0L
    if (-not [long]::TryParse($seedText, [Globalization.NumberStyles]::AllowLeadingSign,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsedSeed) -or
            $seedText -cne $parsedSeed.ToString([Globalization.CultureInfo]::InvariantCulture)) {
        throw "Seed must be a canonical signed 64-bit decimal string: $seedText"
    }
    if (-not $selectedSeeds.Add($seedText)) { throw "Duplicate seed: $seedText" }
}
$centers = @(@(-32, -32), @(32, 32))
$radius = [math]::Floor($CoreSquareSideChunks / 2)
$offsets = -$radius..$radius

if ($PlanOnly) {
    return [pscustomobject]@{
        kind = 'tellurium_original_capture_plan'
        execution = 'NOT_RUN'
        endpoint = $Endpoint
        dimension = $Dimension
        context = $contextDirectoryName
        seeds = $Seeds
        sideChunks = $CoreSquareSideChunks
        caseCount = $Seeds.Count * 2 * $CoreSquareSideChunks * $CoreSquareSideChunks
        timeoutMinutesPerSeed = $TimeoutMinutesPerSeed
        captureRoot = $captureRoot
        runRoot = $runRoot
        independentComparison = $false
        releaseQualification = $false
        reopen = $Reopen.IsPresent
        reopenedCaptureRoot = $reopenedRoot
        frozenCompiledInputs = $FreezeCompiledInputs.IsPresent
    }
}

if ($FreezeCompiledInputs) {
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'FreezeCompiledInputs requires PowerShell 7.' }
    $frozen = Get-WorldgenCompiledInputSnapshot $root
    foreach ($module in @('semantic-core','compiler-jvm','compiler-vulkan','material-codec',
            'spatial-data','chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')) {
        $frozenGradleArgs += @('-x', ":${module}:compileJava", '-x', ":${module}:processResources")
    }
}
function Assert-OriginalCompiledInputs { if ($frozen) { Assert-WorldgenCompiledInputSnapshot $root $frozen.Hash } }

New-Item -ItemType Directory -Force -Path $captureRoot, $runRoot | Out-Null
$captureContextRoot = Join-Path $captureRoot $contextDirectoryName
$runContextRoot = Join-Path $runRoot $contextDirectoryName
if (Test-Path -LiteralPath (Join-Path $captureContextRoot 'capture-inventory.json')) { throw 'Refusing to overwrite an existing original inventory.' }
if ((Test-Path -LiteralPath $captureContextRoot) -and @(Get-ChildItem -LiteralPath $captureContextRoot -Force).Count) { throw 'Original capture context must be empty/fresh.' }
if (Test-Path -LiteralPath $runContextRoot) { throw 'Original run context must be fresh.' }
$reopenedContextRoot = if ($Reopen) { Join-Path $reopenedRoot $contextDirectoryName } else { '' }
if ($Reopen -and (Test-Path -LiteralPath $reopenedContextRoot)) { throw 'Refusing to reuse reopened context root.' }
New-Item -ItemType Directory -Force -Path $captureContextRoot, $runContextRoot | Out-Null
if ($frozen) { [IO.File]::WriteAllText((Join-Path $runContextRoot 'frozen-compiled-inputs.tsv'),$frozen.Manifest,[Text.UTF8Encoding]::new($false)) }
$originalRows=[Collections.Generic.List[object]]::new()
$reopenedRows=[Collections.Generic.List[object]]::new()
$processRows=[Collections.Generic.List[object]]::new()
$modHashes=@($modFiles | ForEach-Object { [pscustomobject]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} })
if ($Reopen) { New-Item -ItemType Directory -Path $reopenedContextRoot -Force | Out-Null }
foreach ($seed in $Seeds) {
    $cases = [System.Collections.Generic.List[object]]::new()
    foreach ($center in $centers) {
        foreach ($dx in $offsets) {
            foreach ($dz in $offsets) {
                $x = $center[0] + $dx
                $z = $center[1] + $dz
                $case = "seed-$seed-x-$x-z-$z-$Endpoint" -replace '[^A-Za-z0-9._-]', '_'
                $output = Join-Path $captureContextRoot "$case.snap"
                if (Test-Path -LiteralPath $output) { throw "Refusing to overwrite existing oracle capture: $output" }
                $cases.Add([pscustomobject]@{ case = $case; chunkX = $x; chunkZ = $z; output = $output })
            }
        }
    }
    $runDir = Join-Path $runContextRoot ("seed-$seed-$Endpoint" -replace '[^A-Za-z0-9._-]', '_')
    if (Test-Path -LiteralPath $runDir) { throw "Refusing to reuse oracle run directory: $runDir" }
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    if ($modFiles.Count -gt 0) {
        $runMods = Join-Path $runDir 'mods'
        New-Item -ItemType Directory -Force -Path $runMods | Out-Null
        $modManifest = [System.Collections.Generic.List[string]]::new()
        foreach ($mod in $modFiles) {
            $destination = Join-Path $runMods $mod.Name
            Copy-Item -LiteralPath $mod.FullName -Destination $destination
            $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            if ($hash -ne ($modHashes | Where-Object { $_.name -ceq $mod.Name }).sha256) { throw 'Mod bytes changed before original capture.' }
            $modManifest.Add("$($mod.Name)`t$($mod.Length)`t$hash")
        }
        [IO.File]::WriteAllLines((Join-Path $runDir 'mod-inputs.tsv'), [string[]]$modManifest,
                [Text.UTF8Encoding]::new($false))
    }
    $manifest = Join-Path $runDir 'cases.tsv'
    # Match the candidate's lexical case order, including signed coordinates.
    $cases=@($cases | Sort-Object case)
    Write-CaseManifest $manifest $cases
    Write-Output "capturing batch $contextDirectoryName/seed-$seed cases=$($cases.Count)"
    $arguments = @(
        '-Dtellurium.oracle.capture=true', '-Dtellurium.oracle.output=',
        "-Dtellurium.oracle.caseFile=$(Gradle-ArgsPath $manifest)",
        "-Dtellurium.oracle.runDir=$(Gradle-ArgsPath $runDir)",
        "-Dtellurium.oracle.seed=$seed",
        "-Dtellurium.oracle.endpoint=$Endpoint",
        "-Dtellurium.oracle.dimension=$Dimension",
        "-Dtellurium.oracle.stack=$StackFingerprint",
        ':oracle-1211:oracleSmoke', '--no-daemon', '--console=plain'
    )
    Assert-OriginalCompiledInputs
    Invoke-ReplayProcess -Command (Join-Path $root 'gradlew.bat') -Arguments ($arguments + $frozenGradleArgs) `
        -WorkingDirectory $root -LogDirectory (Join-Path $runDir 'capture-process') `
        -TimeoutSeconds ($TimeoutMinutesPerSeed * 60)
    Assert-OriginalCompiledInputs
    $processRows.Add([pscustomobject]@{seed=$seed; initialStatus=(Join-Path $runDir 'capture-process/process-status.json')
        reopenedStatus=if ($Reopen) { Join-Path $runDir 'reopened-process/process-status.json' } else { $null }})
    foreach ($captureCase in $cases) {
        if (-not (Test-Path -LiteralPath $captureCase.output)) { throw "Oracle capture did not produce $($captureCase.output)" }
        $firstLine = Get-Content -LiteralPath $captureCase.output -TotalCount 1
        if ($firstLine -ne 'TELLURIUM-SNAPSHOT-1') { throw "Oracle capture has an invalid header: $($captureCase.output)" }
        Write-Output "captured $contextDirectoryName/$($captureCase.case)"
        $originalRows.Add([pscustomobject]@{file=([IO.Path]::GetFileName($captureCase.output)); seed=$seed
            chunkX=$captureCase.chunkX;chunkZ=$captureCase.chunkZ;sha256=(Get-FileHash -LiteralPath $captureCase.output -Algorithm SHA256).Hash})
    }
    if ($Reopen) {
        $reopenCases=@($cases | ForEach-Object {
            [pscustomobject]@{chunkX=$_.chunkX;chunkZ=$_.chunkZ;output=(Join-Path $reopenedContextRoot ([IO.Path]::GetFileName($_.output)))}
        })
        $reopenManifest=Join-Path $runDir 'reopened-cases.tsv'
        Write-CaseManifest $reopenManifest $reopenCases
        $reopenArguments=@($arguments | ForEach-Object {
            if ($_ -like '-Dtellurium.oracle.caseFile=*') { "-Dtellurium.oracle.caseFile=$(Gradle-ArgsPath $reopenManifest)" } else { $_ }
        })
        Assert-OriginalCompiledInputs
        Invoke-ReplayProcess -Command (Join-Path $root 'gradlew.bat') -Arguments ($reopenArguments + $frozenGradleArgs) `
            -WorkingDirectory $root -LogDirectory (Join-Path $runDir 'reopened-process') -TimeoutSeconds ($TimeoutMinutesPerSeed*60)
        Assert-OriginalCompiledInputs
        foreach ($case in $reopenCases) {
            $reopenedRows.Add([pscustomobject]@{file=([IO.Path]::GetFileName($case.output));seed=$seed
                chunkX=$case.chunkX;chunkZ=$case.chunkZ;sha256=(Get-FileHash -LiteralPath $case.output -Algorithm SHA256).Hash})
        }
    }
}
foreach ($mod in $modFiles) {
    if ((Get-FileHash -LiteralPath $mod.FullName -Algorithm SHA256).Hash -ne ($modHashes | Where-Object { $_.name -ceq $mod.Name }).sha256) { throw 'Mod source changed during original capture.' }
}
if ($Reopen) {
    $comparison=Join-Path $runContextRoot 'saved-comparison'
    $dq=[char]34
    $args="compare-corpus $dq$(Gradle-ArgsPath $captureContextRoot)$dq $dq$(Gradle-ArgsPath $reopenedContextRoot)$dq --failure-dir=$(Gradle-ArgsPath $comparison)"
    Invoke-ReplayProcess -Command (Join-Path $root 'gradlew.bat') `
        -Arguments (@(':oracle-and-replay:run',"--args=$args",'--no-daemon','--console=plain') + $frozenGradleArgs) `
        -WorkingDirectory $root -LogDirectory $comparison -TimeoutSeconds ($TimeoutMinutesPerSeed*60)
    Assert-OriginalCompiledInputs
    Assert-LogicalMatrixComparison $comparison $originalRows.Count
}
foreach ($phase in @('INITIAL','REOPENED')) {
    if ($phase -eq 'REOPENED' -and -not $Reopen) { continue }
    $where=if ($phase -eq 'INITIAL') { $captureContextRoot } else { $reopenedContextRoot }
    $rows=if ($phase -eq 'INITIAL') { $originalRows.ToArray() } else { $reopenedRows.ToArray() }
    foreach ($row in $rows) {
        if ((Get-FileHash -LiteralPath (Join-Path $where $row.file) -Algorithm SHA256).Hash -ne $row.sha256) { throw 'Original snapshot changed across capture/comparison.' }
    }
    [ordered]@{schemaVersion=1;kind='tellurium_original_capture_inventory';status='CAPTURED_REFERENCE_ONLY'
        context=$contextDirectoryName;dimension=$Dimension;endpoint=$Endpoint;phase=$phase;stack=$StackFingerprint
        caseCount=$rows.Count;cases=$rows;modHashes=$modHashes;processes=$processRows.ToArray()
        frozenCompiledInputs=$FreezeCompiledInputs.IsPresent;compiledInputsSha256=if ($frozen) { $frozen.Hash } else { $null }
        savedComparisonDirectory=if ($Reopen) { $comparison } else { $null }
        releaseQualification=$false} | ConvertTo-Json -Depth 7 |
        Set-Content -LiteralPath (Join-Path $where 'capture-inventory.json') -Encoding UTF8
}

# Requires PowerShell 7. Manifest paths are relative to the manifest directory.
# JSON: {"schemaVersion":1,"contexts":[{"id":"vanilla-overworld",
# "independentExpectedRoot":"../captures/overworld","modDirectory":""}, ...]}
# Exactly the six IDs below are required. No filters, resume, retries or fallback.
# PlanOnly returns a validated plan to the pipeline and creates no directories.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MatrixManifest,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$RunRoot,
    [ValidateSet('GPU_IEEE_BITS')][string]$Profile = 'GPU_IEEE_BITS',
    [switch]$FreezeCompiledInputs,
    [switch]$SharedStages,
    [switch]$SharedEndIsland,
    [switch]$EnablePipelineOptimization,
    [ValidateRange(1,4000000)][int]$MaxShaderSourceChars = 900000,
    [ValidateRange(1,16384)][int]$BatchElements = 128,
    [ValidateRange(1,180)][int]$TimeoutMinutesPerGroup = 12,
    [ValidateRange(1,1440)][int]$TimeoutMinutesPerContext = 120,
    [ValidateRange(1,8640)][int]$MatrixDeadlineMinutes = 720,
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'GPU matrix requires PowerShell 7 (pwsh).' }
if (-not $FreezeCompiledInputs) { throw 'GPU matrix requires -FreezeCompiledInputs.' }
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$childRunner = Join-Path $PSScriptRoot 'replay-minecraft-noise-gpu.ps1'

function Assert-PlainPath([string]$path) {
    # Reject aliases through junctions/symlinks, including existing ancestors of fresh roots.
    $cursor = [IO.Path]::GetFullPath($path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Reparse path is not admitted: $cursor"
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
}
function Test-PathOverlap([string]$a, [string]$b) {
    $a = [IO.Path]::GetFullPath($a).TrimEnd('\','/')
    $b = [IO.Path]::GetFullPath($b).TrimEnd('\','/')
    return $a.Equals($b, [StringComparison]::OrdinalIgnoreCase) -or
        $a.StartsWith($b + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $b.StartsWith($a + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
function Resolve-MatrixInput([string]$path, [string]$base) {
    if ([string]::IsNullOrWhiteSpace($path)) { throw 'Missing independentExpectedRoot/input path.' }
    $full = [IO.Path]::GetFullPath($path, $base)
    Assert-PlainPath $full
    if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "Missing input directory: $full" }
    return $full
}
function Assert-Properties($obj, [string[]]$allowed) {
    foreach ($key in $obj.Keys) {
        if ($key -cnotin $allowed) { throw "Unknown manifest property: $key" }
    }
}
function Read-NoiseHeader([string]$path) {
    $reader = [IO.File]::OpenText($path)
    $values = @{}
    try {
        if ($reader.ReadLine() -cne 'WORLDGENNEXT-SNAPSHOT-1') { throw "Wrong snapshot header: $path" }
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line -cmatch '^(identity|value=seed|value=dimension|value=endpoint)=(.*)$') {
                if ($values.ContainsKey($Matches[1])) { throw "Duplicate snapshot header: $path" }
                $values[$Matches[1]] = $Matches[2]
            }
        }
    } finally { $reader.Dispose() }
    if ($values.Count -ne 4) { throw "Missing snapshot identity/seed/dimension/endpoint: $path" }
    return $values
}
function Get-MatrixPlan {
    $manifestPath = [IO.Path]::GetFullPath($MatrixManifest)
    Assert-PlainPath $manifestPath
    $json = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
    if ($json -isnot [Collections.IDictionary]) { throw 'Manifest must be a JSON object.' }
    Assert-Properties $json @('schemaVersion','contexts')
    if ($json.schemaVersion -ne 1 -or $json.contexts -isnot [array] -or $json.contexts.Count -ne 6) {
        throw 'Manifest schemaVersion=1 and exactly six contexts are required.'
    }
    $dimensions = [ordered]@{
        'vanilla-overworld' = 'minecraft:overworld'; 'vanilla-nether' = 'minecraft:the_nether'
        'vanilla-end' = 'minecraft:the_end'; 'terralith-overworld' = 'minecraft:overworld'
        'tectonic-overworld' = 'minecraft:overworld'; 'combined-overworld' = 'minecraft:overworld'
    }
    $entries = @{}
    foreach ($entry in $json.contexts) {
        if ($entry -isnot [Collections.IDictionary]) { throw 'Context must be a JSON object.' }
        Assert-Properties $entry @('id','independentExpectedRoot','modDirectory')
        if ($entry.id -cnotin @($dimensions.Keys) -or $entries.ContainsKey($entry.id)) {
            throw "Wrong or duplicate context ID: $($entry.id)"
        }
        $entries[$entry.id] = $entry
    }
    $outputs = [IO.Path]::GetFullPath($OutputRoot)
    $runs = [IO.Path]::GetFullPath($RunRoot)
    foreach ($path in @($outputs,$runs)) {
        Assert-PlainPath $path
        if (Test-Path -LiteralPath $path) { throw "Fresh root required (even empty roots are refused): $path" }
        if (Test-PathOverlap $path $manifestPath) { throw 'Output/run overlaps manifest.' }
    }
    if (Test-PathOverlap $outputs $runs) { throw 'Output/run path overlap.' }
    $contexts = [Collections.Generic.List[object]]::new()
    $inputRoots = [Collections.Generic.List[string]]::new()
    $base = Split-Path -Parent $manifestPath
    $seeds = @('0','12345','-1','9223372036854775807','-9223372036854775808')
    $required = @{}
    foreach ($seed in $seeds) {
        foreach ($center in @(-32,32)) {
            foreach ($x in ($center-2)..($center+2)) {
                foreach ($z in ($center-2)..($center+2)) { $required["$seed/$x/$z"] = $true }
            }
        }
    }
    foreach ($id in $dimensions.Keys) {
        $entry = $entries[$id]
        $expected = Resolve-MatrixInput $entry.independentExpectedRoot $base
        foreach ($other in $inputRoots) {
            if (Test-PathOverlap $expected $other) { throw "Independent context path overlap: $id" }
        }
        $inputRoots.Add($expected)
        $mods = ''
        if (-not [string]::IsNullOrWhiteSpace($entry.modDirectory)) {
            $mods = Resolve-MatrixInput $entry.modDirectory $base
            $jars = @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' -Force)
            if ($jars.Count -eq 0) { throw "ModDirectory contains no jars: $id" }
            foreach ($jar in $jars) { Assert-PlainPath $jar.FullName }
        }
        foreach ($inputPath in @($expected,$mods) | Where-Object { $_ }) {
            foreach ($destination in @($outputs,$runs)) {
                if (Test-PathOverlap $inputPath $destination) { throw "Input/output path overlap: $id" }
            }
        }
        $files = @(Get-ChildItem -LiteralPath $expected -Recurse -Force)
        foreach ($file in $files) {
            if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse corpus entry: $($file.FullName)" }
        }
        $captures = @($files | Where-Object { -not $_.PSIsContainer -and $_.Extension -ieq '.snap' })
        $seen = @{}
        $cases = [Collections.Generic.List[object]]::new()
        foreach ($capture in $captures | Sort-Object FullName) {
            if ($capture.BaseName -cnotmatch '^seed-(?<seed>-?\d+)-x-(?<x>-?\d+)-z-(?<z>-?\d+)-NOISE$') {
                throw "Wrong NOISE case filename: $($capture.Name)"
            }
            $seed = $Matches.seed; $x = $Matches.x; $z = $Matches.z
            $key = "$seed/$x/$z"
            if (-not $required.ContainsKey($key)) { throw "Wrong seed/position or noncanonical signed64 case: $id/$key" }
            if ($seen.ContainsKey($key)) { throw "Duplicate case: $id/$key" }
            $seen[$key] = $true
            $header = Read-NoiseHeader $capture.FullName
            $dimension = $dimensions[$id]
            if ($header['value=seed'] -cne $seed -or $header['value=dimension'] -cne $dimension -or
                $header['value=endpoint'] -cne 'NOISE' -or
                $header.identity -cnotmatch ('^ORIGINAL/[^/]+/' + [regex]::Escape("$seed/$dimension/$x/$z/NOISE/") + '[0-9a-fA-F]{64}$')) {
                throw "Wrong independent snapshot identity/dimension/NOISE header: $($capture.FullName)"
            }
            $relative = [IO.Path]::GetRelativePath($expected,$capture.FullName)
            $cases.Add([pscustomobject]@{ key=$key; relativeCase=($relative -replace '\.snap$',''); expected=$capture.FullName })
        }
        if ($seen.Count -ne 250) { throw "Missing cases: $id needs 250, found $($seen.Count)." }
        $arguments = [ordered]@{
            ExpectedRoot=$expected; OutputRoot=(Join-Path $outputs $id); RunRoot=(Join-Path $runs $id)
            FreezeCompiledInputs=$true; BatchElements=$BatchElements; MaxShaderSourceChars=$MaxShaderSourceChars
            TimeoutMinutesPerGroup=$TimeoutMinutesPerGroup
        }
        foreach ($flag in @('SharedStages','SharedEndIsland','EnablePipelineOptimization')) {
            if ((Get-Variable -Name $flag -ValueOnly).IsPresent) { $arguments[$flag] = $true }
        }
        if ($mods) { $arguments.ModDirectory=$mods }
        $contexts.Add([pscustomobject]@{ id=$id; dimension=$dimension; expectedCases=250; arguments=$arguments; cases=$cases.ToArray() })
    }
    # Metadata inspection only: fail before any child if runner flags drift.
    $supported = (Get-Command -Name $childRunner).Parameters
    foreach ($context in $contexts) {
        foreach ($flag in $context.arguments.Keys) {
            if (-not $supported.ContainsKey($flag)) { throw "Unsupported child flag: $flag" }
        }
    }
    return [pscustomobject]@{
        schemaVersion=1; kind='worldgennext_gpu_noise_matrix'; status='PLAN_ONLY'; profile=$Profile
        expectedCases=1500; expectedFields=15000; qualification=$false; qualificationGate='NOT_G12'
        passedReceipts=0; comparedBlocks=0L; comparedCases=0; comparedFields=0; completeCoverage=$false
        frozenCompiledInputsSha=$null; compiledFingerprintStatus='NOT_RUN'
        outputRoot=$outputs; runRoot=$runs; manifest=$manifestPath; contexts=$contexts.ToArray()
        timeoutMinutesPerContext=$TimeoutMinutesPerContext; matrixDeadlineMinutes=$MatrixDeadlineMinutes
    }
}
function Get-FrozenCompiledInputsSha {
    # Byte-for-byte identical manifest algorithm/module list to the existing child.
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($module in @('semantic-core','compiler-jvm','compiler-vulkan','material-codec','spatial-data',
            'chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/classes/java/main") -PathType Container)) {
            throw "Frozen replay requires existing compiled classes: $module"
        }
        foreach ($kind in @('classes/java/main','resources/main')) {
            $directory = Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/$kind"
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            foreach ($file in Get-ChildItem -LiteralPath $directory -File -Recurse) {
                $relative = [IO.Path]::GetRelativePath($directory,$file.FullName).Replace('\','/')
                $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                $lines.Add("$module/$kind/$relative`t$($file.Length)`t$hash")
            }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
}
function Invoke-MatrixChild($context, [double]$minutes) {
    $directory = $context.arguments.RunRoot
    New-Item -ItemType Directory -Path $directory | Out-Null
    $quote = { param($s) "'" + ([string]$s).Replace("'","''") + "'" }
    $code = '$ErrorActionPreference = ''Stop''; try { & ' + (& $quote $childRunner)
    foreach ($key in $context.arguments.Keys) {
        $value = $context.arguments[$key]
        $code += ' -' + $key
        if ($value -isnot [bool]) { $code += ' ' + (& $quote $value) }
    }
    $code += '; exit 0 } catch { Write-Error $_ -ErrorAction Continue; exit 1 }'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
    $process = $null; $exited = $false; $exitCode = $null; $status = 'FAIL'; $errorText = $null
    $killAttempted=$false; $killExitCode=$null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $process = Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -PassThru `
            -ArgumentList "-NoProfile -NonInteractive -EncodedCommand $encoded" -WorkingDirectory $repoRoot `
            -RedirectStandardOutput (Join-Path $directory 'matrix-child-stdout.log') `
            -RedirectStandardError (Join-Path $directory 'matrix-child-stderr.log')
        while (-not $process.WaitForExit(1000)) {
            if ($timer.Elapsed.TotalMinutes -ge $minutes) { $status='TIMEOUT'; break }
        }
        if ($status -ne 'TIMEOUT') { $status = if ($process.ExitCode -eq 0) { 'COMPLETED' } else { 'FAIL' } }
    } catch { $errorText=$_.Exception.Message }
    finally {
        if ($null -ne $process) {
            try {
                if (-not $process.HasExited) {
                    $killAttempted=$true
                    & (Join-Path $env:SystemRoot 'System32/taskkill.exe') /PID $process.Id /T /F | Out-Null
                    $killExitCode=$LASTEXITCODE
                    [void]$process.WaitForExit(15000)
                }
                if ($process.HasExited) { $process.WaitForExit(); $exitCode=$process.ExitCode }
                $exited=$process.HasExited -and (-not $killAttempted -or $killExitCode -eq 0)
            } catch { $errorText = "$errorText; cleanup: $($_.Exception.Message)" }
            $process.Dispose()
        } else { $exited=$true }
        $timer.Stop()
    }
    return [pscustomobject]@{ status=$status; exitCode=$exitCode; workerExited=$exited; error=$errorText
        processTreeKillAttempted=$killAttempted; processTreeKillExitCode=$killExitCode; elapsedMillis=$timer.ElapsedMilliseconds }
}
function Measure-MatrixContext($context, [string]$sha) {
    $receipts=0; $blocks=0L; $compared=0; $errorText=$null
    # Failed children may have receipts but no final report. Bind even those counts
    # to the child's original frozen manifest, rather than assuming process identity.
    $frozenPath=Join-Path $context.arguments.RunRoot 'frozen-compiled-inputs.tsv'
    $childSha=$null
    if (Test-Path -LiteralPath $frozenPath -PathType Leaf) {
        $childSha=(Get-FileHash -LiteralPath $frozenPath -Algorithm SHA256).Hash
    }
    if ($childSha -ne $sha) {
        return [pscustomobject]@{ passedReceipts=0; comparedBlocks=0L; comparedCases=0; comparedFields=0
            childFrozenCompiledInputsSha=$childSha; error='Missing or changed child frozen manifest; counts unbound.' }
    }
    $valid = @{}
    foreach ($case in $context.cases) {
        $artifact = Join-Path $context.arguments.OutputRoot ($case.relativeCase + '.chunk')
        $receipt = $artifact + '.gpu-receipt.json'
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or -not (Test-Path -LiteralPath $receipt -PathType Leaf)) { continue }
        try {
            $r = Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
            if ($r.status -ceq 'PASS' -and $r.route -ceq 'GPU_IEEE_BITS' -and $r.resultAbi -ceq 'chunk-result-v4' -and
                $r.compilerVersion -ceq 'worldgennext-gpu-live-v0.2' -and $r.comparedBlocks -gt 0 -and
                $null -ne $r.mismatches -and $r.mismatches -eq 0 -and $r.shaderHash -and $r.spirvHash -and $null -ne $r.device) {
                $receipts++; $blocks += [long]$r.comparedBlocks; $valid[$case.relativeCase.Replace('\','/')]=$r
            }
        } catch { $errorText=$_.Exception.Message }
    }
    $reportPath = Join-Path $context.arguments.OutputRoot 'replay-report.json'
    try {
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw 'No independent comparison report.' }
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        if ($report.kind -cne 'worldgennext_gpu_noise_replay' -or $report.status -cne 'PASS' -or
            $report.route -cne 'GPU_IEEE_BITS' -or $report.frozenCompiledInputs -ne $true -or
            $report.compiledInputsSha256 -ne $sha -or $report.completeCoverage -ne $true -or
            $report.selectedSubset -ne $false -or $report.independentOracle -ne $true -or
            $null -eq $report.mismatches -or $report.mismatches -ne 0 -or $report.comparedCases -ne 250 -or
            $report.comparedFields -ne 2500 -or @($report.cases).Count -ne 250 -or $receipts -ne 250) {
            throw 'Incomplete comparison report or frozen compiled input SHA mismatch.'
        }
        $seen = @{}
        foreach ($case in $context.cases) {
            $key=$case.relativeCase.Replace('\','/')
            $rows=@($report.cases | Where-Object { $_.case -ceq $key })
            $artifact=Join-Path $context.arguments.OutputRoot ($case.relativeCase + '.chunk')
            if ($rows.Count -ne 1 -or $rows[0].comparisonExitCode -ne 0 -or $null -eq $rows[0].comparisonExitCode -or
                $rows[0].expected -ne $case.expected -or $rows[0].actual -ne $artifact -or
                $rows[0].receipt -ne ($artifact + '.gpu-receipt.json') -or $rows[0].comparedBlocks -ne $valid[$key].comparedBlocks) {
                throw "Comparison case binding failed: $key"
            }
            $seen[$key]=$true
        }
        $compared=$seen.Count
    } catch { $errorText=$_.Exception.Message }
    return [pscustomobject]@{ passedReceipts=$receipts; comparedBlocks=$blocks; comparedCases=$compared
        comparedFields=($compared*10); childFrozenCompiledInputsSha=$childSha; error=$errorText }
}
function Invoke-SerialMatrix($plan) {
    $sha=Get-FrozenCompiledInputsSha
    $plan.frozenCompiledInputsSha=$sha; $plan.compiledFingerprintStatus='BOUND'; $plan.status='RUNNING'
    New-Item -ItemType Directory -Path $plan.outputRoot,$plan.runRoot | Out-Null
    $results=[Collections.Generic.List[object]]::new()
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $stopReason=$null
    foreach ($context in $plan.contexts) {
        $result=[ordered]@{ id=$context.id; status='NOT_RUN'; expectedCases=250; passedReceipts=0; comparedBlocks=0L
            comparedCases=0; comparedFields=0; completeCoverage=$false; frozenCompiledInputsSha=$sha
            childFrozenCompiledInputsSha=$null
            outputRoot=$context.arguments.OutputRoot; runRoot=$context.arguments.RunRoot; process=$null; error=$stopReason }
        try {
            if (-not $stopReason) {
                $remaining=$MatrixDeadlineMinutes-$timer.Elapsed.TotalMinutes
                if ($remaining -le 0) { $stopReason='Matrix deadline exhausted.'; throw $stopReason }
                if ((Get-FrozenCompiledInputsSha) -ne $sha) { $stopReason='Frozen inputs changed before child.'; throw $stopReason }
                $remaining=$MatrixDeadlineMinutes-$timer.Elapsed.TotalMinutes
                if ($remaining -le 0) { $stopReason='Matrix deadline exhausted.'; throw $stopReason }
                $process=Invoke-MatrixChild $context ([math]::Min($TimeoutMinutesPerContext,$remaining))
                $result.process=$process; $result.status= if ($process.status -eq 'COMPLETED') { 'FAIL' } else { $process.status }
                if (-not $process.workerExited) {
                    $stopReason='Owned child exit unproven; no further child admitted.'
                    $result.error=$stopReason # Do not inspect still-changing artifacts.
                } else {
                    $metrics=Measure-MatrixContext $context $sha
                    foreach ($key in @('passedReceipts','comparedBlocks','comparedCases','comparedFields')) { $result[$key]=$metrics.$key }
                    $result.childFrozenCompiledInputsSha=$metrics.childFrozenCompiledInputsSha
                    $result.error=if ($process.error) { $process.error } else { $metrics.error }
                    if ((Get-FrozenCompiledInputsSha) -ne $sha) {
                        $stopReason='Frozen inputs changed during child.'; $result.status='FAIL'
                        $result.comparedCases=0; $result.comparedFields=0; $result.error=$stopReason
                    } elseif ($process.status -eq 'COMPLETED' -and $process.exitCode -eq 0 -and
                        -not $result.error -and $metrics.childFrozenCompiledInputsSha -eq $sha -and $metrics.comparedCases -eq 250) {
                        $result.status='PASS'; $result.completeCoverage=$true
                    }
                }
            }
        } catch { $result.error=$_.Exception.Message; if ($result.status -ne 'NOT_RUN') { $result.status='FAIL' }; $stopReason=$_.Exception.Message }
        $results.Add([pscustomobject]$result)
        $plan.contexts=$results.ToArray() # Checkpoint after every terminal child, preserving original child files.
        foreach ($key in @('passedReceipts','comparedBlocks','comparedCases','comparedFields')) {
            $plan.$key=($results | Measure-Object -Property $key -Sum).Sum
        }
        $plan | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $plan.outputRoot 'matrix-report.json') -Encoding utf8
    }
    $timer.Stop()
    foreach ($key in @('passedReceipts','comparedBlocks','comparedCases','comparedFields')) {
        $plan.$key=($results | Measure-Object -Property $key -Sum).Sum
    }
    $plan.completeCoverage=(@($results | Where-Object status -eq 'PASS').Count -eq 6 -and $plan.comparedCases -eq 1500)
    $plan.status=if ($plan.completeCoverage) { 'PASS' } elseif (@($results | Where-Object status -eq 'TIMEOUT').Count -gt 0 -or $stopReason -eq 'Matrix deadline exhausted.') { 'TIMEOUT' } else { 'FAIL' }
    $plan | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $plan.outputRoot 'matrix-report.json') -Encoding utf8
    return $plan
}

$plan = Get-MatrixPlan
if ($PlanOnly) { return $plan }
$report = Invoke-SerialMatrix $plan
$report
if ($report.status -ne 'PASS') { throw "GPU matrix $($report.status); artifacts retained at $($report.outputRoot)." }

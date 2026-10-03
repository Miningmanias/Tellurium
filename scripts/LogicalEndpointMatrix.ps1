# Pure plan/report validation. Dot-sourcing never starts Minecraft or Vulkan.
function Assert-LogicalMatrixPlainPath([string]$path) {
    $cursor = [IO.Path]::GetFullPath($path)
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and
                ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Reparse path is not admitted: $cursor"
        }
        $cursor = Split-Path -Parent $cursor
    }
}
function Test-LogicalMatrixOverlap([string]$a, [string]$b) {
    $a = [IO.Path]::GetFullPath($a).TrimEnd('\','/')
    $b = [IO.Path]::GetFullPath($b).TrimEnd('\','/')
    return $a.Equals($b, [StringComparison]::OrdinalIgnoreCase) -or
        $a.StartsWith($b + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $b.StartsWith($a + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
function Test-LogicalMatrixChildPath([string]$path, [string]$parent) {
    return [IO.Path]::GetFullPath($path).StartsWith(
        [IO.Path]::GetFullPath($parent).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)
}
function Get-LogicalMatrixSnapshots([string]$root) {
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($root)
    while ($pending.Count) {
        foreach ($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse corpus entry: $($entry.FullName)" }
            if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
            elseif ($entry.Extension -ieq '.snap') { $entry }
        }
    }
}
function Read-LogicalMatrixHeader([string]$path) {
    $reader = [IO.File]::OpenText($path)
    $values = @{}; $fields = @{}
    $required = @('BLOCK_STATES','BIOMES','HEIGHTMAPS','POSTPROCESSING','LIGHT','TICKS',
        'BLOCK_ENTITIES','STRUCTURES','ENTITIES','LIFECYCLE')
    try {
        if ($reader.ReadLine() -cne 'WORLDGENNEXT-SNAPSHOT-1') { throw "Wrong snapshot header: $path" }
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line -cmatch '^(identity|value=seed|value=dimension|value=endpoint)=(.*)$') {
                if ($values.ContainsKey($Matches[1])) { throw "Duplicate identity metadata: $path" }
                $values[$Matches[1]] = $Matches[2]
            } elseif ($line.StartsWith('field=', [StringComparison]::Ordinal)) {
                $end = $line.IndexOf('=', 6)
                if ($end -lt 0) { throw "Malformed snapshot field: $path" }
                $name = $line.Substring(6, $end - 6)
                if ($name -cnotin $required -or $fields.ContainsKey($name)) { throw "Unknown/duplicate field $name in $path" }
                $fields[$name] = $true
            }
        }
    } finally { $reader.Dispose() }
    if ($values.Count -ne 4 -or $fields.Count -ne 10) { throw "Incomplete snapshot header/field coverage: $path" }
    return $values
}
function Assert-LogicalOriginalInventory($context, [string]$phase) {
    $root=if ($phase -eq 'INITIAL') { $context.expectedRoot } else { $context.reopenedExpectedRoot }
    $inventoryPath=Join-Path $root 'capture-inventory.json'
    Assert-LogicalMatrixPlainPath $inventoryPath
    $i=Get-Content -LiteralPath $inventoryPath -Raw | ConvertFrom-Json
    if ($i.schemaVersion -ne 1 -or $i.kind -cne 'worldgennext_original_capture_inventory' -or
            $i.status -cne 'CAPTURED_REFERENCE_ONLY' -or $i.context -cne $context.id -or
            $i.dimension -cne $context.dimension -or $i.endpoint -cne $context.endpoint -or $i.phase -cne $phase -or
            $i.stack -cne 'minecraft-1.21.1-neoforge-21.1.176' -or $i.caseCount -ne $context.cases.Count -or
            @($i.cases).Count -ne $context.cases.Count) { throw 'Missing/wrong original capture inventory; labels alone are not stack evidence.' }
    $loaded=@($i.modHashes | ForEach-Object { $_.sha256.ToUpperInvariant() } | Sort-Object)
    $planned=@($context.modHashes | ForEach-Object { $_.sha256.ToUpperInvariant() } | Sort-Object)
    if (($loaded -join "`n") -cne ($planned -join "`n")) { throw 'Original/candidate loaded mod identities differ.' }
    foreach ($case in $context.cases) {
        $file=if ($phase -eq 'INITIAL') { $case.expected } else { $case.reopenedExpected }
        $hash=if ($phase -eq 'INITIAL') { $case.expectedSha256 } else { $case.reopenedExpectedSha256 }
        $relative=[IO.Path]::GetRelativePath($root,$file)
        $rows=@($i.cases | Where-Object { $_.file -ceq $relative })
        if ($rows.Count -ne 1 -or $rows[0].sha256 -ne $hash -or $rows[0].seed -cne $case.seed -or
                $rows[0].chunkX -ne $case.chunkX -or $rows[0].chunkZ -ne $case.chunkZ) { throw 'Original capture case/hash provenance mismatch.' }
    }
    $seeds=@($context.cases.seed | Sort-Object -Unique)
    if (@($i.processes).Count -ne $seeds.Count) { throw 'Original process seed inventory mismatch.' }
    foreach ($seed in $seeds) {
        $p=@($i.processes | Where-Object { $_.seed -ceq $seed })
        if ($p.Count -ne 1) { throw 'Duplicate/missing original process seed.' }
        $statusPath=if ($phase -eq 'INITIAL') { $p[0].initialStatus } else { $p[0].reopenedStatus }
        Assert-LogicalMatrixPlainPath $statusPath
        $status=Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
        if ($status.status -cne 'PROCESS_COMPLETED' -or $status.exitCode -ne 0 -or $null -eq $status.exitCode -or
                $status.workerExited -ne $true -or $status.timedOut -ne $false -or $status.processTreeKillAttempted -ne $false) { throw 'Original process did not complete cleanly.' }
    }
    if ($phase -eq 'REOPENED') { Assert-LogicalMatrixComparison $i.savedComparisonDirectory $context.cases.Count }
    return (Get-FileHash -LiteralPath $inventoryPath -Algorithm SHA256).Hash
}
function New-LogicalEndpointMatrixPlan {
    param([string]$ManifestPath, [ValidateSet('NOISE','FULL','SAVED')][string]$Endpoint,
        [ValidateSet('CPU_OWNED','GPU_IEEE_BITS')][string]$Backend, [string]$OutputRoot, [string]$RunRoot)
    $manifest = [IO.Path]::GetFullPath($ManifestPath)
    Assert-LogicalMatrixPlainPath $manifest
    $json = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json -AsHashtable
    if ($json -isnot [Collections.IDictionary] -or $json.schemaVersion -ne 1 -or
            $json.contexts -isnot [array] -or $json.contexts.Count -ne 6) { throw 'Manifest requires schemaVersion=1 and exactly six contexts.' }
    foreach ($key in $json.Keys) { if ($key -cnotin @('schemaVersion','contexts')) { throw "Unknown manifest property: $key" } }
    $dimensions = [ordered]@{'vanilla-overworld'='minecraft:overworld'; 'vanilla-nether'='minecraft:the_nether';
        'vanilla-end'='minecraft:the_end'; 'terralith-overworld'='minecraft:overworld';
        'tectonic-overworld'='minecraft:overworld'; 'combined-overworld'='minecraft:overworld'}
    $output = [IO.Path]::GetFullPath($OutputRoot); $runs = [IO.Path]::GetFullPath($RunRoot)
    foreach ($root in @($output,$runs)) {
        Assert-LogicalMatrixPlainPath $root
        if (Test-Path -LiteralPath $root) { throw "Fresh matrix root required: $root" }
        if (Test-LogicalMatrixOverlap $root $manifest) { throw 'Output/run overlaps input manifest.' }
    }
    if (Test-LogicalMatrixOverlap $output $runs) { throw 'Output/run overlap.' }
    $seeds = if ($Endpoint -eq 'NOISE') { @('0','12345','-1','9223372036854775807','-9223372036854775808') } else { @('0','12345') }
    $radius = if ($Endpoint -eq 'NOISE') { 2 } else { 1 }
    $required = @{}
    foreach ($seed in $seeds) { foreach ($center in @(-32,32)) {
        foreach ($x in ($center-$radius)..($center+$radius)) { foreach ($z in ($center-$radius)..($center+$radius)) {
            $required["$seed/$x/$z"] = $true
        }}
    }}
    $base = Split-Path -Parent $manifest
    $seenContexts = @{}; $inputRoots = [Collections.Generic.List[string]]::new()
    $contexts = [Collections.Generic.List[object]]::new()
    $lockPath=Join-Path $PSScriptRoot '../test-manifest/dependencies.lock.json'
    $lock=Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
    foreach ($entry in $json.contexts) {
        if ($entry -isnot [Collections.IDictionary]) { throw 'Context must be an object.' }
        foreach ($key in $entry.Keys) {
            if ($key -cnotin @('id','independentExpectedRoot','reopenedExpectedRoot','modDirectory')) { throw "Unknown context property: $key" }
        }
        $id = $entry.id
        if ($id -cnotin @($dimensions.Keys) -or $seenContexts.ContainsKey($id)) { throw "Wrong/duplicate context ID: $id" }
        $seenContexts[$id] = $true
        $expected = ''
        $reopened = ''
        foreach ($kind in @('independentExpectedRoot','reopenedExpectedRoot')) {
            if ($kind -eq 'reopenedExpectedRoot' -and $Endpoint -ne 'SAVED') {
                if ($entry[$kind]) { throw 'Reopened originals are only accepted for SAVED.' }
                continue
            }
            if ([string]::IsNullOrWhiteSpace($entry[$kind])) { throw "Missing $kind for $id" }
            $resolved = [IO.Path]::GetFullPath($entry[$kind], $base)
            Assert-LogicalMatrixPlainPath $resolved
            if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw "Missing input directory: $resolved" }
            foreach ($other in @($output,$runs) + $inputRoots.ToArray()) {
                if (Test-LogicalMatrixOverlap $resolved $other) { throw "Input/root overlap: $id" }
            }
            $inputRoots.Add($resolved)
            if ($kind -eq 'independentExpectedRoot') { $expected = $resolved } else { $reopened = $resolved }
        }
        $mods = ''
        $modHashes = @()
        if ($entry.modDirectory) {
            $mods = [IO.Path]::GetFullPath($entry.modDirectory, $base)
            Assert-LogicalMatrixPlainPath $mods
            foreach ($root in @($output,$runs)) { if (Test-LogicalMatrixOverlap $mods $root) { throw "Mod/output overlap: $id" } }
            $jars = @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' -Force)
            if (-not $jars.Count) { throw "Mod directory contains no jars: $id" }
            $modHashes = @($jars | ForEach-Object {
                Assert-LogicalMatrixPlainPath $_.FullName
                [pscustomobject]@{path=$_.FullName; sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
            })
        } elseif ($id -notlike 'vanilla-*') { throw "Required terrain-mod context needs modDirectory: $id" }
        $wanted=@(switch ($id) {
            'terralith-overworld' { @('Terralith','Lithostitched') }
            'tectonic-overworld' { @('Tectonic','Lithostitched') }
            'combined-overworld' { @('Terralith','Tectonic','Lithostitched') }
            default { @() }
        })
        $pins=@($lock.terrainMods | Where-Object { $_.id -cin $wanted } | ForEach-Object { $_.sha256.ToUpperInvariant() } | Sort-Object)
        $actualPins=@($modHashes | ForEach-Object { $_.sha256.ToUpperInvariant() } | Sort-Object)
        if ($pins.Count -ne @($wanted).Count -or ($pins -join "`n") -cne ($actualPins -join "`n")) { throw "Loaded mod set does not match pinned context: $id" }
        $cases = [Collections.Generic.List[object]]::new(); $seen = @{}
        foreach ($capture in @(Get-LogicalMatrixSnapshots $expected | Sort-Object FullName)) {
            if ($capture.BaseName -cnotmatch ('^seed-(?<seed>-?\d+)-x-(?<x>-?\d+)-z-(?<z>-?\d+)-' + $Endpoint + '$')) { throw "Wrong case filename: $($capture.Name)" }
            $seed=$Matches.seed; $x=$Matches.x; $z=$Matches.z; $key="$seed/$x/$z"
            if (-not $required.ContainsKey($key) -or $seen.ContainsKey($key)) { throw "Wrong/duplicate core case: $id/$key" }
            $seen[$key]=$true
            $relative = [IO.Path]::GetRelativePath($expected, $capture.FullName)
            $reopenPath = if ($reopened) { Join-Path $reopened $relative } else { $null }
            foreach ($path in @($capture.FullName,$reopenPath) | Where-Object { $_ }) {
                Assert-LogicalMatrixPlainPath $path
                $h = Read-LogicalMatrixHeader $path
                $suffix = "$seed/$($dimensions[$id])/$x/$z/$Endpoint/"
                if ($h['value=seed'] -cne $seed -or $h['value=dimension'] -cne $dimensions[$id] -or
                        $h['value=endpoint'] -cne $Endpoint -or
                        $h.identity -cnotmatch ('^ORIGINAL/[^/]+/' + [regex]::Escape($suffix) + '[0-9a-fA-F]{64}$')) { throw "Wrong independent identity: $path" }
            }
            $cases.Add([pscustomobject]@{
                key=$key; relativeCase=($relative -replace '\.snap$','').Replace('\','/'); seed=$seed; chunkX=[int]$x; chunkZ=[int]$z
                expected=$capture.FullName; expectedSha256=(Get-FileHash -LiteralPath $capture.FullName -Algorithm SHA256).Hash
                reopenedExpected=$reopenPath; reopenedExpectedSha256=if ($reopenPath) { (Get-FileHash -LiteralPath $reopenPath -Algorithm SHA256).Hash } else { $null }
            })
        }
        if ($seen.Count -ne $required.Count) { throw "Incomplete $Endpoint core corpus: $id ($($seen.Count)/$($required.Count))" }
        if ($reopened -and @(Get-LogicalMatrixSnapshots $reopened).Count -ne $required.Count) { throw "Reopened case-set mismatch: $id" }
        $context=[pscustomobject]@{id=$id; endpoint=$Endpoint; dimension=$dimensions[$id]; expectedRoot=$expected; reopenedExpectedRoot=$reopened
            modDirectory=$mods; modHashes=$modHashes; cases=$cases.ToArray()
            dependencyLockPath=$lockPath; dependencyLockSha256=(Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash
            originalInventorySha256=$null; reopenedInventorySha256=$null
            outputRoot=(Join-Path $output $id); runRoot=(Join-Path (Join-Path $runs $id) 'game-runs')}
        $context.originalInventorySha256=Assert-LogicalOriginalInventory $context 'INITIAL'
        if ($reopened) { $context.reopenedInventorySha256=Assert-LogicalOriginalInventory $context 'REOPENED' }
        $contexts.Add($context)
    }
    return [pscustomobject]@{schemaVersion=1; kind='worldgennext_logical_endpoint_matrix'; status='PLAN_ONLY'
        endpoint=$Endpoint; backend=$Backend; expectedCases=($required.Count*6); expectedFields=($required.Count*60)
        expectedReopenedCases=if ($Endpoint -eq 'SAVED') { $required.Count*6 } else { 0 }
        outputRoot=$output; runRoot=$runs; contexts=$contexts.ToArray(); releaseQualification=$false; productionHookEnabled=$false}
}
function Assert-LogicalMatrixInputHashes($context) {
    if ((Get-FileHash -LiteralPath $context.dependencyLockPath -Algorithm SHA256).Hash -ne $context.dependencyLockSha256) { throw 'Pinned dependency lock changed.' }
    $current=@(Get-LogicalMatrixSnapshots $context.expectedRoot | ForEach-Object { $_.FullName } | Sort-Object)
    $planned=@($context.cases | ForEach-Object { $_.expected } | Sort-Object)
    if (($current -join "`n") -cne ($planned -join "`n")) { throw 'Original snapshot inventory changed.' }
    if ($context.reopenedExpectedRoot) {
        $current=@(Get-LogicalMatrixSnapshots $context.reopenedExpectedRoot | ForEach-Object { $_.FullName } | Sort-Object)
        $planned=@($context.cases | ForEach-Object { $_.reopenedExpected } | Sort-Object)
        if (($current -join "`n") -cne ($planned -join "`n")) { throw 'Reopened snapshot inventory changed.' }
    }
    if ($context.modDirectory) {
        $current=@(Get-ChildItem -LiteralPath $context.modDirectory -File -Filter '*.jar' -Force | ForEach-Object { $_.FullName } | Sort-Object)
        $planned=@($context.modHashes | ForEach-Object { $_.path } | Sort-Object)
        if (($current -join "`n") -cne ($planned -join "`n")) { throw 'Loaded mod inventory changed.' }
    }
    foreach ($case in $context.cases) {
        foreach ($pair in @(@($case.expected,$case.expectedSha256),@($case.reopenedExpected,$case.reopenedExpectedSha256))) {
            if ($pair[0]) {
                Assert-LogicalMatrixPlainPath $pair[0]
                if ((Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash -ne $pair[1]) { throw "Original changed: $($pair[0])" }
            }
        }
    }
    foreach ($jar in $context.modHashes) {
        Assert-LogicalMatrixPlainPath $jar.path
        if ((Get-FileHash -LiteralPath $jar.path -Algorithm SHA256).Hash -ne $jar.sha256) { throw "Mod input changed: $($jar.path)" }
    }
    if ((Assert-LogicalOriginalInventory $context 'INITIAL') -ne $context.originalInventorySha256) { throw 'Original inventory changed.' }
    if ($context.reopenedExpectedRoot -and (Assert-LogicalOriginalInventory $context 'REOPENED') -ne $context.reopenedInventorySha256) { throw 'Reopened original inventory changed.' }
}
function Read-LogicalNoiseAbiHeader([string]$path) {
    $file=Get-Item -LiteralPath $path
    if ($file.Length -lt 40 -or $file.Length -gt 128*1024*1024) { throw 'NOISE artifact exceeds bounded ABI.' }
    $reader=[IO.BinaryReader]::new([IO.File]::OpenRead($path), [Text.UTF8Encoding]::new($false,$true))
    try {
        if ($reader.ReadInt32() -ne 0x57474E43 -or $reader.ReadInt32() -ne 4) { throw 'Wrong NOISE ABI magic/version.' }
        $x=$reader.ReadInt32(); $z=$reader.ReadInt32(); $minY=$reader.ReadInt32(); $height=$reader.ReadInt32()
        if ($height -le 0 -or $height % 16 -ne 0 -or [long]$minY+$height -gt [int]::MaxValue) { throw 'Invalid NOISE geometry.' }
        $strings=@()
        for ($i=0;$i -lt 3;$i++) {
            $n=$reader.ReadInt32()
            if ($n -le 0 -or $n -gt 1024) { throw 'Invalid bounded NOISE identity length.' }
            $bytes=$reader.ReadBytes($n)
            if ($bytes.Length -ne $n) { throw 'Truncated NOISE identity.' }
            $strings += [Text.UTF8Encoding]::new($false,$true).GetString($bytes)
        }
        if ($strings[2] -cne 'chunk-result-v4') { throw 'Wrong NOISE ABI name.' }
        return [pscustomobject]@{chunkX=$x;chunkZ=$z;minY=$minY;height=$height;storageBlocks=([long]$height*256)
            contextKey=$strings[0];registryFingerprint=$strings[1]}
    } finally { $reader.Dispose() }
}
function Assert-LogicalMatrixComparison([string]$directory, [int]$count) {
    $p = Get-Content -LiteralPath (Join-Path $directory 'process-status.json') -Raw | ConvertFrom-Json
    if ($p.status -cne 'PROCESS_COMPLETED' -or $p.exitCode -ne 0 -or $null -eq $p.exitCode -or
            $p.workerExited -ne $true -or $p.timedOut -ne $false -or $p.processTreeKillAttempted -ne $false) { throw 'Independent comparison process did not complete cleanly.' }
    $pattern = "^PASS CORPUS_COMPARISON cases=$count expectedCases=$count actualCases=$count fields=$($count*10) failures=0 reason=equal$"
    if (@(Get-Content -LiteralPath (Join-Path $directory 'process-stdout.log') | Where-Object { $_ -cmatch $pattern }).Count -ne 1) { throw 'Missing/miscounted independent comparison evidence.' }
}
function Assert-LogicalEndpointContextReport {
    param($Context, [string]$Endpoint, [string]$Backend, [string]$CompiledSha256)
    Assert-LogicalMatrixInputHashes $Context
    $report = Get-Content -LiteralPath (Join-Path $Context.outputRoot 'logical-endpoint-report.json') -Raw | ConvertFrom-Json
    $n = $Context.cases.Count; $saved = $Endpoint -eq 'SAVED'
    $status = if ($saved) { 'PASS_CANDIDATE_VERIFICATION_ONLY' } else { 'PASS_CANDIDATE_ENDPOINT_ONLY' }
    if ($report.kind -cne 'worldgennext_candidate_logical_endpoint_replay' -or $report.status -cne $status -or
            $report.endpoint -cne $Endpoint -or $report.route -cne $Backend -or $report.coordinatorDispatch -cne 'INLINE_REFERENCE' -or
            $report.frozenCompiledInputs -ne $true -or $report.compiledInputsSha256 -ne $CompiledSha256 -or
            $report.releaseQualification -ne $false -or $report.productionHookEnabled -ne $false -or
            $report.independentComparison -cne 'PASS' -or $report.caseCount -ne $n -or $report.comparedCases -ne $n -or
            $report.comparedFields -ne $n*10 -or @($report.cases).Count -ne $n -or $report.reopenVerified -ne $saved -or
            $report.initialExpectedRoot -ne $Context.expectedRoot -or $report.runRoot -ne $Context.runRoot -or
            $report.initialCandidateRoot -ne (Join-Path $Context.outputRoot 'initial') -or
            $report.reopenedComparedCases -ne $(if ($saved) { $n } else { 0 }) -or
            $report.reopenedComparedFields -ne $(if ($saved) { $n*10 } else { 0 })) { throw 'Incomplete/unbound logical context report.' }
    if ((Get-FileHash -LiteralPath (Join-Path $Context.runRoot 'frozen-compiled-inputs.tsv') -Algorithm SHA256).Hash -ne $CompiledSha256) { throw 'Child frozen manifest mismatch.' }
    Assert-LogicalMatrixComparison (Join-Path $Context.outputRoot 'failures-initial') $n
    if ($saved) {
        if ($report.reopenedExpectedRoot -ne $Context.reopenedExpectedRoot -or
                $report.reopenedCandidateRoot -ne (Join-Path $Context.outputRoot 'reopened')) { throw 'Wrong reopened corpus roots.' }
        Assert-LogicalMatrixComparison (Join-Path $Context.outputRoot 'failures-reopened') $n
    }
    $seen=@{}; $seenBundles=@{}; $gpuStates=0L
    foreach ($case in $Context.cases) {
        $rows=@($report.cases | Where-Object { $_.case -ceq $case.relativeCase })
        if ($rows.Count -ne 1 -or $seen.ContainsKey($case.relativeCase)) { throw 'Duplicate/missing comparison row.' }
        $seen[$case.relativeCase]=$true; $row=$rows[0]
        if ($row.seed -cne $case.seed -or $row.chunkX -ne $case.chunkX -or $row.chunkZ -ne $case.chunkZ -or
                $row.dimension -cne $Context.dimension) { throw 'Case coordinates/context changed.' }
        foreach ($phase in @('initial','reopened')) {
            if ($phase -eq 'reopened' -and -not $saved) { continue }
            $expected = if ($phase -eq 'initial') { $case.expected } else { $case.reopenedExpected }
            $expectedSha = if ($phase -eq 'initial') { $case.expectedSha256 } else { $case.reopenedExpectedSha256 }
            $actual=Join-Path (Join-Path $Context.outputRoot $phase) ($case.relativeCase + '.snap')
            if ($row."${phase}Expected" -ne $expected -or $row."${phase}ExpectedSha256" -ne $expectedSha -or
                    $row."${phase}Candidate" -ne $actual) { throw 'Case artifact-path/hash binding failed.' }
            Assert-LogicalMatrixPlainPath $actual
            if ((Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash -ne $row."${phase}CandidateSha256") { throw 'Candidate snapshot changed after comparison.' }
            $h=Read-LogicalMatrixHeader $actual
            $suffix="$($case.seed)/$($Context.dimension)/$($case.chunkX)/$($case.chunkZ)/$Endpoint/"
            if ($h['value=seed'] -cne $case.seed -or $h['value=dimension'] -cne $Context.dimension -or
                    $h['value=endpoint'] -cne $Endpoint -or
                    $h.identity -cnotmatch ('^CANDIDATE/[^/]+/' + [regex]::Escape($suffix) + '[0-9a-fA-F]{64}$')) { throw 'Candidate snapshot case identity mismatch.' }
        }
        if (-not $row.liveBatchRunRoot -or -not (Test-LogicalMatrixChildPath $row.liveBatchRunRoot $Context.runRoot)) { throw 'Missing/outside NOISE batch binding.' }
        Assert-LogicalMatrixPlainPath $row.liveBatchRunRoot
        if ((Get-FileHash -LiteralPath (Join-Path $row.liveBatchRunRoot 'server.properties') -Algorithm SHA256).Hash -ne $row.batchPropertiesSha256) { throw 'NOISE seed properties changed after comparison.' }
        $seedLines=@(Get-Content -LiteralPath (Join-Path $row.liveBatchRunRoot 'server.properties') | Where-Object { $_ -cmatch '^level-seed=' })
        if ($seedLines.Count -ne 1 -or $seedLines[0] -cne ('level-seed='+$case.seed)) { throw 'NOISE evidence belongs to another seed.' }
        $stem=Join-Path (Join-Path $row.liveBatchRunRoot 'live-noise-results') "chunk-$($case.chunkX)-$($case.chunkZ)"
        $suffix=if ($Backend -eq 'GPU_IEEE_BITS') { '.gpu-receipt.json' } else { '.cpu-receipt.json' }
        if ($row.noiseArtifact -ne ($stem+'.chunk') -or $row.noiseStatus -ne ($stem+'.candidate-status') -or
                $row.backendReceipt -ne ($stem+$suffix) -or $seenBundles.ContainsKey($stem)) { throw 'Wrong/reused core publication bundle.' }
        $seenBundles[$stem]=$true
        foreach ($path in @($row.noiseArtifact,$row.noiseStatus,$row.backendReceipt)) {
            if (-not $path -or -not (Test-LogicalMatrixChildPath $path $Context.runRoot)) { throw 'Live evidence outside child run.' }
            Assert-LogicalMatrixPlainPath $path
            if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -le 0) { throw 'Missing live evidence.' }
        }
        foreach ($property in @('noiseStatus','backendReceipt')) {
            if ((Get-FileHash -LiteralPath $row.$property -Algorithm SHA256).Hash -ne $row."${property}Sha256") { throw "Live evidence changed after comparison: $property" }
        }
        $terminal=@(Get-Content -LiteralPath $row.noiseStatus)
        if ($terminal.Count -ne 2 -or $terminal[0] -cne 'PASS' -or $terminal[1] -cne 'COMMITTED') { throw 'NOISE was not published.' }
        $r=Get-Content -LiteralPath $row.backendReceipt -Raw | ConvertFrom-Json
        if ((Get-FileHash -LiteralPath $row.noiseArtifact -Algorithm SHA256).Hash -ne $row.noiseArtifactSha256) { throw 'NOISE evidence changed after comparison.' }
        $abi=Read-LogicalNoiseAbiHeader $row.noiseArtifact
        if ($abi.chunkX -ne $case.chunkX -or $abi.chunkZ -ne $case.chunkZ -or $abi.contextKey -cne $r.contextKey -or
                $abi.registryFingerprint -cnotmatch '^[0-9a-fA-F]{64}$') { throw 'NOISE ABI/receipt/context binding mismatch.' }
        if ($Backend -eq 'GPU_IEEE_BITS') {
            if ($row.publicationReceipt -ne ($stem+'.gpu-commit.json')) { throw 'Missing/wrong GPU publication receipt.' }
            Assert-LogicalMatrixPlainPath $row.publicationReceipt
            if ((Get-FileHash -LiteralPath $row.publicationReceipt -Algorithm SHA256).Hash -ne $row.publicationReceiptSha256) { throw 'GPU publication evidence changed after comparison.' }
            $c=Get-Content -LiteralPath $row.publicationReceipt -Raw | ConvertFrom-Json
            if ($r.kind -cne 'worldgennext_gpu_live_receipt' -or $r.status -cne 'BACKEND_VALIDATED' -or $r.route -cne $Backend -or
                    $r.resultAbi -cne 'chunk-result-v4' -or $r.compilerVersion -cne 'worldgennext-gpu-live-v0.2' -or
                    $r.shaderHash -cnotmatch '^[0-9a-fA-F]{64}$' -or $r.spirvHash -cnotmatch '^[0-9a-fA-F]{64}$' -or
                    $null -eq $r.device -or -not $r.executionId -or $r.submitted -le 0 -or $r.completed -ne $r.submitted -or
                    $r.validated -ne $r.completed -or $r.committed -ne 0 -or
                    $r.logicalGpuElements -ne $(if ($Context.dimension -eq 'minecraft:overworld') { 98304 } else { 32768 }) -or
                    $r.storageBlocks -ne $abi.storageBlocks -or $r.storageBlocks -ne $r.submitted -or
                    $c.kind -cne 'worldgennext_gpu_live_commit' -or $c.status -cne 'COMMITTED' -or $c.route -cne $Backend -or
                    $c.committed -ne $r.validated -or $r.chunkX -ne $case.chunkX -or $r.chunkZ -ne $case.chunkZ) { throw 'Invalid GPU execution/publication evidence.' }
            foreach ($key in @('executionId','contextKey','snapshotHash','programHash','chunkX','chunkZ','worldEpoch','deviceGeneration')) {
                if ($null -eq $r.$key -or $r.$key -cne $c.$key) { throw "Unlinked GPU receipt: $key" }
            }
            $gpuStates += [long]$c.committed
        } elseif ($r.kind -cne 'worldgennext_cpu_live_receipt' -or $r.status -cne 'BACKEND_VALIDATED' -or
                $r.route -cne 'CPU_OWNED' -or $r.resultAbi -cne 'chunk-result-v4' -or
                $r.compilerVersion -cne 'worldgennext-cpu-live-v0.2' -or -not $r.executionId -or
                $r.submitted -ne $abi.storageBlocks -or $r.completed -ne $r.submitted -or
                $r.validated -ne $r.completed -or $r.committed -ne $r.validated) { throw 'Invalid CPU-owned execution evidence.' }
    }
    return [pscustomobject]@{comparedCases=$n; comparedFields=($n*10); reopenedComparedCases=if ($saved) { $n } else { 0 }
        reopenedComparedFields=if ($saved) { $n*10 } else { 0 }; gpuCorePublications=if ($Backend -eq 'GPU_IEEE_BITS') { $n } else { 0 }
        gpuCommittedStorageStates=$gpuStates; reportPath=(Join-Path $Context.outputRoot 'logical-endpoint-report.json')}
}

# Original-only serial producer for replay-minecraft-logical-matrix.ps1.
[CmdletBinding()]
param(
    [string]$ContextManifest = (Join-Path $PSScriptRoot '../test-manifest/v0.2-gpu-matrix-local.json'),
    [Parameter(Mandatory)][string]$CaptureRoot,
    [Parameter(Mandatory)][string]$RunRoot,
    [ValidateSet('NOISE','FULL','SAVED')][string]$Endpoint = 'FULL',
    [ValidateRange(1,180)][int]$TimeoutMinutesPerSeed = 25,
    [ValidateRange(1,720)][int]$TimeoutMinutesPerContext = 180,
    [ValidateRange(1,8640)][int]$MatrixDeadlineMinutes = 1440,
    [switch]$RepeatOriginal,
    [switch]$FreezeCompiledInputs,
    [switch]$PlanOnly
)
$ErrorActionPreference='Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Original matrix requires PowerShell 7.' }
if (-not $FreezeCompiledInputs) { throw 'Original matrix requires -FreezeCompiledInputs.' }
. (Join-Path $PSScriptRoot 'LogicalEndpointMatrix.ps1')
. (Join-Path $PSScriptRoot 'CompiledReplayInputs.ps1')
. (Join-Path $PSScriptRoot 'Invoke-ReplayProcess.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifest=(Resolve-Path -LiteralPath $ContextManifest).Path
$capture=[IO.Path]::GetFullPath($CaptureRoot);$runs=[IO.Path]::GetFullPath($RunRoot)
foreach($path in @($manifest,$capture,$runs)){Assert-LogicalMatrixPlainPath $path}
if ((Test-LogicalMatrixOverlap $capture $runs) -or (Test-LogicalMatrixOverlap $capture $manifest) -or
        (Test-LogicalMatrixOverlap $runs $manifest)) { throw 'Capture, run and context-manifest paths must be disjoint.' }
foreach($path in @($capture,$runs)){if(Test-Path -LiteralPath $path){throw "Fresh matrix root required: $path"}}
$inputContexts=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json
$dimensions=[ordered]@{'vanilla-overworld'='minecraft:overworld';'vanilla-nether'='minecraft:the_nether';
    'vanilla-end'='minecraft:the_end';'terralith-overworld'='minecraft:overworld';
    'tectonic-overworld'='minecraft:overworld';'combined-overworld'='minecraft:overworld'}
if ($inputContexts.schemaVersion -ne 1 -or @($inputContexts.contexts).Count -ne 6) { throw 'Exactly six schema-1 contexts are required.' }
$endpoint=$Endpoint.ToUpperInvariant()
$seeds=if($endpoint -eq 'NOISE'){@('0','12345','-1','9223372036854775807','-9223372036854775808')}else{@('0','12345')}
$side=if($endpoint -eq 'NOISE'){5}else{3};$count=$seeds.Count*2*$side*$side
$lock=Get-Content -LiteralPath (Join-Path $repo 'test-manifest/dependencies.lock.json') -Raw|ConvertFrom-Json
$seen=@{};$contexts=@();$entries=@()
foreach($entry in $inputContexts.contexts){
    $id=[string]$entry.id
    if(-not $dimensions.Contains($id) -or $seen.ContainsKey($id)){throw "Unknown/duplicate context: $id"};$seen[$id]=$true
    $mods='';$hashes=@()
    if($entry.modDirectory){
        $mods=[IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $manifest) $entry.modDirectory))
        Assert-LogicalMatrixPlainPath $mods
        foreach($path in @($capture,$runs)){if(Test-LogicalMatrixOverlap $mods $path){throw 'Mod inputs overlap capture/run paths.'}}
        $hashes=@(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar' -Force|ForEach-Object{
            Assert-LogicalMatrixPlainPath $_.FullName
            [pscustomobject]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
        })
    }
    $wanted=@(switch($id){'terralith-overworld'{@('Terralith','Lithostitched')}
        'tectonic-overworld'{@('Tectonic','Lithostitched')}'combined-overworld'{@('Terralith','Tectonic','Lithostitched')}default{@()}})
    $pins=@($lock.terrainMods|Where-Object{$_.id -cin $wanted}|ForEach-Object{$_.sha256.ToUpperInvariant()}|Sort-Object)
    $actual=@($hashes|ForEach-Object{$_.sha256.ToUpperInvariant()}|Sort-Object)
    if($pins.Count -ne $wanted.Count -or ($pins -join "`n") -cne ($actual -join "`n")){throw "Incorrect pinned mod stack: $id"}
    $contexts+= [pscustomobject]@{id=$id;dimension=$dimensions[$id];modDirectory=$mods;modHashes=$hashes}
    $row=[ordered]@{id=$id;independentExpectedRoot=(Join-Path $capture "initial/$id")}
    if($mods){$row.modDirectory=$mods}
    if($endpoint -eq 'SAVED'){$row.reopenedExpectedRoot=Join-Path $capture "reopened/$id"}
    $entries+=$row
}
$plan=[pscustomobject]@{schemaVersion=1;kind='worldgennext_original_endpoint_matrix';status='PLAN_ONLY';endpoint=$endpoint;
    expectedCases=($count*6);expectedRepeatCases=if($RepeatOriginal){$count*6}else{0};
    expectedReopenedCases=if($endpoint -eq 'SAVED'){$count*6}else{0};
    captureRoot=$capture;runRoot=$runs;contexts=$contexts;releaseQualification=$false;productionHookEnabled=$false}
if($PlanOnly){return $plan}
$frozen=Get-WorldgenCompiledInputSnapshot $repo
New-Item -ItemType Directory -Path $capture,$runs|Out-Null
[IO.File]::WriteAllText((Join-Path $runs 'frozen-compiled-inputs.tsv'),$frozen.Manifest,[Text.UTF8Encoding]::new($false))
$candidateManifest=Join-Path $capture 'logical-matrix-manifest.json'
@{schemaVersion=1;contexts=$entries}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $candidateManifest -Encoding UTF8
$timer=[Diagnostics.Stopwatch]::StartNew();$results=[Collections.Generic.List[object]]::new();$failure=$null
$shell=(Get-Process -Id $PID).Path;$runner=Join-Path $PSScriptRoot 'capture-original-corpus.ps1'
$quote={param([string]$value) "'"+$value.Replace("'","''")+"'"}
function New-OriginalCaptureInvocation($arguments,[bool]$reopen){
    $command='& '+(& $quote $runner)
    foreach($key in $arguments.Keys){$command+=' -'+$key+' '+(& $quote ([string]$arguments[$key]))}
    $command+=' -Seeds @('+ (($seeds|ForEach-Object{& $quote $_}) -join ',') +') -FreezeCompiledInputs'
    if($reopen){$command+=' -Reopen'}
    return $command
}
function Write-CaptureProgress([string]$status){
    [ordered]@{schemaVersion=1;kind=$plan.kind;status=$status;endpoint=$endpoint;expectedCases=$plan.expectedCases;
        expectedRepeatCases=$plan.expectedRepeatCases;expectedReopenedCases=$plan.expectedReopenedCases;
        compiledInputsSha256=$frozen.Hash;frozenCompiledInputs=$true;releaseQualification=$false;productionHookEnabled=$false;
        matrixManifest=$candidateManifest;elapsedMillis=$timer.ElapsedMilliseconds;failure=$failure;contexts=$results.ToArray()}|
        ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $capture 'capture-matrix-report.json') -Encoding UTF8
}
Write-CaptureProgress RUNNING
foreach($context in $contexts){
    $row=[pscustomobject]@{id=$context.id;status='NOT_RUN';caseCount=0;repeatComparison='NOT_RUN';error=$failure}
    if(-not $failure){try{
        Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
        foreach($jar in $context.modHashes){if((Get-FileHash -LiteralPath $jar.path -Algorithm SHA256).Hash -ne $jar.sha256){throw 'Pinned mod input changed.'}}
        $remaining=$MatrixDeadlineMinutes*60-$timer.Elapsed.TotalSeconds
        if($remaining -le 0){throw 'Original matrix deadline exhausted.'}
        $budget=[int][Math]::Max(1,[Math]::Floor([Math]::Min($TimeoutMinutesPerContext*60,$remaining)))
        $arguments=[ordered]@{Endpoint=$endpoint;Dimension=$context.dimension;ContextId=$context.id;
            CaptureRoot=(Join-Path $capture 'initial');RunRoot=(Join-Path $runs 'initial');
            CoreSquareSideChunks=$side;TimeoutMinutesPerSeed=$TimeoutMinutesPerSeed}
        if($context.modDirectory){$arguments.ModDirectory=$context.modDirectory}
        if($endpoint -eq 'SAVED'){$arguments.ReopenedCaptureRoot=Join-Path $capture 'reopened'}
        $code='$ErrorActionPreference=''Stop''; try { '+(New-OriginalCaptureInvocation $arguments ($endpoint -eq 'SAVED'))
        if($RepeatOriginal){
            # Repeat is a second clean world, not a second reopened pass.
            $repeatArguments=[ordered]@{}
            foreach($key in $arguments.Keys){if($key -ne 'ReopenedCaptureRoot'){$repeatArguments[$key]=$arguments[$key]}}
            $repeatArguments.CaptureRoot=Join-Path $capture 'repeat'
            $repeatArguments.RunRoot=Join-Path $runs 'repeat'
            $code+='; '+(New-OriginalCaptureInvocation $repeatArguments $false)
        }
        $code+='; exit 0 } catch { Write-Error $_ -ErrorAction Continue; exit 1 }'
        Write-Host "Original matrix endpoint=$endpoint context=$($context.id) cores=$count repeat=$($RepeatOriginal.IsPresent)"
        Invoke-ReplayProcess -Command $shell -Arguments @('-NoProfile','-NonInteractive','-EncodedCommand',
            [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))) -WorkingDirectory $repo `
            -LogDirectory (Join-Path $runs "$($context.id)/capture-worker") -TimeoutSeconds $budget
        Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
        if($RepeatOriginal){
            $comparison=Join-Path $runs "$($context.id)/repeat-comparison";$dq=[char]34
            $args="compare-corpus $dq$(Join-Path $capture "initial/$($context.id)")$dq $dq$(Join-Path $capture "repeat/$($context.id)")$dq --failure-dir=$dq$comparison$dq"
            $skip=@();foreach($module in @('semantic-core','compiler-jvm','compiler-vulkan','material-codec','spatial-data','chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')){$skip+=@('-x',":${module}:compileJava",'-x',":${module}:processResources")}
            $remaining=$MatrixDeadlineMinutes*60-$timer.Elapsed.TotalSeconds
            if($remaining -le 0){throw 'Original matrix deadline exhausted before repeat comparison.'}
            Invoke-ReplayProcess -Command (Join-Path $repo 'gradlew.bat') -Arguments (@(':oracle-and-replay:run',"--args=$args",'--no-daemon','--console=plain')+$skip) `
                -WorkingDirectory $repo -LogDirectory $comparison -TimeoutSeconds ([int][Math]::Max(1,[Math]::Min($TimeoutMinutesPerSeed*60,$remaining)))
            Assert-LogicalMatrixComparison $comparison $count;$row.repeatComparison='PASS'
            Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
        }
        $row.caseCount=$count;$row.status='CAPTURED_REFERENCE_ONLY'
    }catch{$failure=$_.Exception.Message;$row.status='FAIL';$row.error=$failure}}
    $results.Add($row);Write-CaptureProgress $(if($failure){'FAIL'}else{'RUNNING'})
}
if(-not $failure){try{
    # This validates all produced inventories, pins, identities and exact core sets.
    New-LogicalEndpointMatrixPlan $candidateManifest $endpoint GPU_IEEE_BITS `
        (Join-Path $capture 'future-candidates') (Join-Path $runs 'future-candidates')|Out-Null
    Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
}catch{$failure=$_.Exception.Message}}
$timer.Stop();Write-CaptureProgress $(if($failure){'FAIL'}else{'PASS_REFERENCE_CAPTURE_MATRIX'})
if($failure){throw "Original endpoint matrix failed: $failure"}
Write-Host "CAPTURED original endpoint=$endpoint cases=$($plan.expectedCases) manifest=$candidateManifest (NOT_RELEASE_QUALIFICATION)"

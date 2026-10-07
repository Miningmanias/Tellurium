# Synthetic planning/report fixtures only. No game, shader compiler or native calls.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../LogicalEndpointMatrix.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$root=Join-Path ([IO.Path]::GetTempPath()) ('tellurium-logical-matrix-test-'+[guid]::NewGuid())
$checks=0
function Check([bool]$condition,[string]$message) {
    if (-not $condition) { throw $message }; $script:checks++
}
function Reject([scriptblock]$action,[string]$message) {
    $failed=$false; try { & $action | Out-Null } catch { $failed=$true }; Check $failed $message
}
function Json([string]$path,$object) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path,($object|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
}
function Snapshot([string]$path,[string]$source,[string]$seed,[string]$dimension,[int]$x,[int]$z,[string]$endpoint) {
    $lines=@('TELLURIUM-SNAPSHOT-1',"identity=$source/minecraft-1.21.1-neoforge-21.1.176/$seed/$dimension/$x/$z/$endpoint/$('a'*64)")
    foreach($f in @('BLOCK_STATES','BIOMES','HEIGHTMAPS','POSTPROCESSING','LIGHT','TICKS','BLOCK_ENTITIES','STRUCTURES','ENTITIES','LIFECYCLE')) {$lines+="field=$f={}"}
    $lines+=@("value=seed=$seed","value=dimension=$dimension","value=endpoint=$endpoint")
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force|Out-Null
    [IO.File]::WriteAllLines($path,$lines,[Text.UTF8Encoding]::new($false))
}
function Hash([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
function Comparison([string]$dir,[int]$count) {
    Json (Join-Path $dir 'process-status.json') @{status='PROCESS_COMPLETED';exitCode=0;workerExited=$true;timedOut=$false;processTreeKillAttempted=$false}
    [IO.File]::WriteAllText((Join-Path $dir 'process-stdout.log'),"PASS CORPUS_COMPARISON cases=$count expectedCases=$count actualCases=$count fields=$($count*10) failures=0 reason=equal`n")
}
function Noise([string]$path,[int]$x,[int]$z) {
    $writer=[IO.BinaryWriter]::new([IO.File]::Create($path))
    try {
        foreach($value in @(0x57474E43,4,$x,$z,-64,384)) {$writer.Write([int]$value)}
        foreach($value in @(('b'*64),('c'*64),'chunk-result-v4')) {
            $bytes=[Text.Encoding]::UTF8.GetBytes($value);$writer.Write([int]$bytes.Length);$writer.Write($bytes)
        }
    } finally {$writer.Dispose()}
}
try {
    New-Item -ItemType Directory -Path $root | Out-Null
    $manifest=Join-Path $root 'manifest.json';$out=Join-Path $root 'out';$run=Join-Path $root 'run'
    $ids=@('vanilla-overworld','vanilla-nether','vanilla-end','terralith-overworld','tectonic-overworld','combined-overworld')
    $modDirs=@{'terralith-overworld'='terralith-2.6.2-lithostitched-1.8.0b6-20260913';
        'tectonic-overworld'='tectonic-3.0.26-lithostitched-1.8.0b6-20260913';
        'combined-overworld'='combined-terralith-tectonic-lithostitched-20260913'}
    $entries=@()
    foreach($id in $ids) {
        $dim=switch($id){'vanilla-nether'{'minecraft:the_nether'}'vanilla-end'{'minecraft:the_end'}default{'minecraft:overworld'}}
        $expected=Join-Path $root "inputs/$id";$rows=@();$procs=@();$mods=@();$modDir=''
        if($modDirs.ContainsKey($id)) {
            $modDir=Join-Path $repo "build/terrain-mods/$($modDirs[$id])"
            $mods=@(Get-ChildItem -LiteralPath $modDir -Filter '*.jar' -File|ForEach-Object{ @{name=$_.Name;sha256=(Hash $_.FullName)} })
        }
        foreach($seed in @('0','12345')) {
            $status=Join-Path $root "original-process/$id/$seed.json"
            Json $status @{status='PROCESS_COMPLETED';exitCode=0;workerExited=$true;timedOut=$false;processTreeKillAttempted=$false}
            $procs+=@{seed=$seed;initialStatus=$status;reopenedStatus=$null}
            foreach($center in @(-32,32)){foreach($x in ($center-1)..($center+1)){foreach($z in ($center-1)..($center+1)){
                $file="seed-$seed-x-$x-z-$z-FULL.snap";$path=Join-Path $expected $file
                Snapshot $path 'ORIGINAL' $seed $dim $x $z 'FULL'
                $rows+=@{file=$file;seed=$seed;chunkX=$x;chunkZ=$z;sha256=(Hash $path)}
            }}}
        }
        Json (Join-Path $expected 'capture-inventory.json') @{schemaVersion=1;kind='tellurium_original_capture_inventory';status='CAPTURED_REFERENCE_ONLY';
            context=$id;dimension=$dim;endpoint='FULL';phase='INITIAL';stack='minecraft-1.21.1-neoforge-21.1.176';caseCount=36;cases=$rows;modHashes=$mods;processes=$procs}
        $entries+=@{id=$id;independentExpectedRoot=$expected;modDirectory=$modDir}
    }
    Json $manifest @{schemaVersion=1;contexts=$entries}
    $p=New-LogicalEndpointMatrixPlan $manifest FULL GPU_IEEE_BITS $out $run
    Check ($p.expectedCases -eq 216 -and $p.expectedFields -eq 2160 -and $p.contexts.Count -eq 6) 'exact FULL denominator'
    Check ($p.status -eq 'PLAN_ONLY' -and -not $p.releaseQualification) 'plan is not a generation pass'
    Check (-not(Test-Path $out) -and -not(Test-Path $run)) 'plan writes no output roots'
    $ctx=$p.contexts[0];Assert-LogicalMatrixInputHashes $ctx;$checks++
    $extra=Join-Path $ctx.expectedRoot 'extra.snap';[IO.File]::WriteAllText($extra,'extra')
    Reject {Assert-LogicalMatrixInputHashes $ctx} 'added capture fails inventory'
    Remove-Item -LiteralPath $extra
    $first=$ctx.cases[0];$old=[IO.File]::ReadAllBytes($first.expected)
    [IO.File]::AppendAllText($first.expected,"value=unknown=changed`n")
    Reject {Assert-LogicalMatrixInputHashes $ctx} 'changed original bytes fail'
    [IO.File]::WriteAllBytes($first.expected,$old)
    Reject {New-LogicalEndpointMatrixPlan $manifest SAVED GPU_IEEE_BITS $out $run} 'missing reopened corpus fails'
    Reject {New-LogicalEndpointMatrixPlan $manifest FULL CPU_OWNED $out $out} 'overlapping roots fail'
    $changed=@($entries);$changed[5]=$entries[0]
    Json (Join-Path $root 'duplicate.json') @{schemaVersion=1;contexts=$changed}
    Reject {New-LogicalEndpointMatrixPlan (Join-Path $root 'duplicate.json') FULL GPU_IEEE_BITS $out $run} 'duplicate context fails'
    $inventory=Join-Path $ctx.expectedRoot 'capture-inventory.json';$inventoryBytes=[IO.File]::ReadAllBytes($inventory)
    Remove-Item -LiteralPath $inventory
    Reject {New-LogicalEndpointMatrixPlan $manifest FULL GPU_IEEE_BITS $out $run} 'unattested context label fails'
    [IO.File]::WriteAllBytes($inventory,$inventoryBytes)
    $inv=Get-Content -LiteralPath $inventory -Raw|ConvertFrom-Json
    $inv.cases[0].sha256='0'*64;Json $inventory $inv
    Reject {New-LogicalEndpointMatrixPlan $manifest FULL GPU_IEEE_BITS $out $run} 'wrong capture provenance hash fails'
    [IO.File]::WriteAllBytes($inventory,$inventoryBytes)
    $frozenText="synthetic-main-manifest`n";$frozenHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($frozenText)))
    New-Item -ItemType Directory -Path $ctx.runRoot -Force|Out-Null
    [IO.File]::WriteAllText((Join-Path $ctx.runRoot 'frozen-compiled-inputs.tsv'),$frozenText)
    $reportRows=@()
    foreach($c in $ctx.cases) {
        $candidate=Join-Path $ctx.outputRoot ('initial/'+$c.relativeCase+'.snap')
        Snapshot $candidate 'CANDIDATE' $c.seed $ctx.dimension $c.chunkX $c.chunkZ FULL
        $batch=Join-Path $ctx.runRoot "seed-$($c.seed)";$evidence=Join-Path $batch 'live-noise-results'
        New-Item -ItemType Directory -Path $evidence -Force|Out-Null
        [IO.File]::WriteAllText((Join-Path $batch 'server.properties'),"level-seed=$($c.seed)`n")
        $stem=Join-Path $evidence "chunk-$($c.chunkX)-$($c.chunkZ)";Noise ($stem+'.chunk') $c.chunkX $c.chunkZ
        [IO.File]::WriteAllText(($stem+'.candidate-status'),"PASS`nCOMMITTED`n")
        $r=@{kind='tellurium_gpu_live_receipt';status='BACKEND_VALIDATED';route='GPU_IEEE_BITS';resultAbi='chunk-result-v4';compilerVersion='tellurium-gpu-live-v0.2';
            shaderHash=('d'*64);spirvHash=('e'*64);device=@{name='MODEL_ONLY'};executionId=$c.key;contextKey=('b'*64);snapshotHash=('f'*64);programHash=('a'*64);
            chunkX=$c.chunkX;chunkZ=$c.chunkZ;worldEpoch=0;deviceGeneration=0;submitted=98304;completed=98304;validated=98304;committed=0;logicalGpuElements=98304;storageBlocks=98304}
        Json ($stem+'.gpu-receipt.json') $r
        $commit=@{kind='tellurium_gpu_live_commit';status='COMMITTED';route='GPU_IEEE_BITS';committed=98304}
        foreach($k in @('executionId','contextKey','snapshotHash','programHash','chunkX','chunkZ','worldEpoch','deviceGeneration')){$commit[$k]=$r[$k]}
        Json ($stem+'.gpu-commit.json') $commit
        $reportRows+=@{case=$c.relativeCase;seed=$c.seed;chunkX=$c.chunkX;chunkZ=$c.chunkZ;dimension=$ctx.dimension;
            initialExpected=$c.expected;initialExpectedSha256=$c.expectedSha256;initialCandidate=$candidate;initialCandidateSha256=(Hash $candidate);
            liveBatchRunRoot=$batch;noiseArtifact=($stem+'.chunk');noiseArtifactSha256=(Hash ($stem+'.chunk'));noiseStatus=($stem+'.candidate-status');
            batchPropertiesSha256=(Hash (Join-Path $batch 'server.properties'));noiseStatusSha256=(Hash ($stem+'.candidate-status'));
            backendReceipt=($stem+'.gpu-receipt.json');backendReceiptSha256=(Hash ($stem+'.gpu-receipt.json'));
            publicationReceipt=($stem+'.gpu-commit.json');publicationReceiptSha256=(Hash ($stem+'.gpu-commit.json'))}
    }
    $report=@{kind='tellurium_candidate_logical_endpoint_replay';status='PASS_CANDIDATE_ENDPOINT_ONLY';endpoint='FULL';route='GPU_IEEE_BITS';
        coordinatorDispatch='INLINE_REFERENCE';frozenCompiledInputs=$true;compiledInputsSha256=$frozenHash;releaseQualification=$false;productionHookEnabled=$false;
        independentComparison='PASS';caseCount=36;comparedCases=36;comparedFields=360;cases=$reportRows;reopenVerified=$false;reopenedComparedCases=0;reopenedComparedFields=0;
        initialExpectedRoot=$ctx.expectedRoot;runRoot=$ctx.runRoot;initialCandidateRoot=(Join-Path $ctx.outputRoot 'initial')}
    $reportPath=Join-Path $ctx.outputRoot 'logical-endpoint-report.json';Json $reportPath $report
    Comparison (Join-Path $ctx.outputRoot 'failures-initial') 36
    $result=Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash
    Check ($result.comparedCases -eq 36 -and $result.gpuCorePublications -eq 36) 'valid MODEL report binds all cores'
    $candidate=$reportRows[0].initialCandidate;[IO.File]::AppendAllText($candidate,'changed')
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'post-comparison snapshot mutation fails'
    Snapshot $candidate CANDIDATE $reportRows[0].seed $ctx.dimension $reportRows[0].chunkX $reportRows[0].chunkZ FULL
    $receipt=$reportRows[0].backendReceipt;$savedReceipt=[IO.File]::ReadAllBytes($receipt)
    $r=Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json
    $r.submitted=1;$r.completed=1;$r.validated=1;$r.storageBlocks=1;$r.logicalGpuElements=1;Json $receipt $r
    $reportRows[0].backendReceiptSha256=Hash $receipt;Json $reportPath $report
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'partial GPU counters fail ABI geometry'
    [IO.File]::WriteAllBytes($receipt,$savedReceipt)
    $reportRows[0].backendReceiptSha256=Hash $receipt;Json $reportPath $report
    [IO.File]::AppendAllText($receipt,"`n")
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'post-comparison receipt mutation fails'
    [IO.File]::WriteAllBytes($receipt,$savedReceipt)
    $batch=$reportRows[0].liveBatchRunRoot
    $reportRows[0].liveBatchRunRoot=$ctx.runRoot;Json $reportPath $report
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'equal run root is not a batch child'
    $reportRows[0].liveBatchRunRoot=Split-Path -Parent $ctx.runRoot;Json $reportPath $report
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'ancestor run root is not a batch child'
    $reportRows[0].liveBatchRunRoot=$batch;Json $reportPath $report
    $commitPath=$reportRows[0].publicationReceipt;$commitBytes=[IO.File]::ReadAllBytes($commitPath)
    [IO.File]::AppendAllText($commitPath,"`n")
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'post-comparison publication mutation fails'
    [IO.File]::WriteAllBytes($commitPath,$commitBytes)
    $reportRows[0].liveBatchRunRoot=$reportRows[-1].liveBatchRunRoot;Json $reportPath $report
    Reject {Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash} 'seed-bundle reuse fails'
    $reportRows[0].liveBatchRunRoot=$batch;Json $reportPath $report
    Check ((Assert-LogicalEndpointContextReport $ctx FULL GPU_IEEE_BITS $frozenHash).comparedCases -eq 36) 'restored MODEL fixtures remain valid'
    Write-Host "PASS logical matrix MODEL_TOOLING assertions=$checks (no generation/native/comparator execution)"
} finally {
    $absolute=[IO.Path]::GetFullPath($root);$temporary=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($temporary,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($absolute) -notlike 'tellurium-logical-matrix-test-*') {throw 'Unsafe fixture cleanup path'}
    if(Test-Path -LiteralPath $absolute){Remove-Item -LiteralPath $absolute -Recurse -Force}
}

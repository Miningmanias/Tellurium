# Driver-free contract tests. Tiny synthetic headers/receipts are NOT GPU evidence.
# Run: pwsh -NoProfile -File scripts/tests/test-gpu-matrix-contract.ps1
$ErrorActionPreference='Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Tests require PowerShell 7.' }
$matrix=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../replay-minecraft-noise-gpu-matrix.ps1'))
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('worldgennext-matrix-contract-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
$checks=0
function Assert($condition,[string]$message) {
    if (-not $condition) { throw "Assertion failed: $message" }
    $script:checks++
}
function Reject([scriptblock]$action,[string]$message) {
    $caught=$null
    try { & $action | Out-Null } catch { $caught=$_.Exception.Message }
    Assert ($null -ne $caught -and $caught -match $message) "Expected rejection /$message/, got: $caught"
}
function Save-Manifest($contexts) {
    [IO.File]::WriteAllText($manifest,(@{schemaVersion=1;contexts=@($contexts)} | ConvertTo-Json -Depth 5))
}
function Write-FakeSnapshot([string]$path,[string]$seed,[int]$x,[int]$z,[string]$dimension) {
    [IO.File]::WriteAllLines($path,[string[]]@('WORLDGENNEXT-SNAPSHOT-1',
        "identity=ORIGINAL/test-stack/$seed/$dimension/$x/$z/NOISE/$('a'*64)",
        "value=seed=$seed","value=dimension=$dimension",'value=endpoint=NOISE'))
}
try {
    $dimensions=[ordered]@{'vanilla-overworld'='minecraft:overworld';'vanilla-nether'='minecraft:the_nether'
        'vanilla-end'='minecraft:the_end';'terralith-overworld'='minecraft:overworld'
        'tectonic-overworld'='minecraft:overworld';'combined-overworld'='minecraft:overworld'}
    $entries=@()
    foreach ($id in $dimensions.Keys) {
        $dir=Join-Path $testRoot $id
        New-Item -ItemType Directory -Path $dir | Out-Null
        foreach ($seed in @('0','12345','-1','9223372036854775807','-9223372036854775808')) {
            foreach ($center in @(-32,32)) {
                foreach ($x in ($center-2)..($center+2)) {
                    foreach ($z in ($center-2)..($center+2)) {
                        Write-FakeSnapshot (Join-Path $dir "seed-$seed-x-$x-z-$z-NOISE.snap") $seed $x $z $dimensions[$id]
                    }
                }
            }
        }
        $entries+=@{id=$id;independentExpectedRoot=$id}
    }
    $manifest=Join-Path $testRoot 'matrix.json'
    Save-Manifest $entries
    $options=@{MatrixManifest=$manifest;OutputRoot=(Join-Path $testRoot 'fresh-output')
        RunRoot=(Join-Path $testRoot 'fresh-runs');FreezeCompiledInputs=$true}
    $plan=& $matrix @options -PlanOnly
    Assert ($plan.expectedCases -eq 1500 -and $plan.contexts.Count -eq 6) 'Complete planned coverage'
    Assert (-not $plan.qualification -and -not $plan.completeCoverage -and $plan.passedReceipts -eq 0 -and
        $plan.comparedFields -eq 0 -and $null -eq $plan.frozenCompiledInputsSha) 'Plan is not execution evidence'
    Assert (-not (Test-Path $options.OutputRoot) -and -not (Test-Path $options.RunRoot)) 'PlanOnly creates no output/run roots'
    Assert (($plan.contexts.id -join ',') -ceq ($dimensions.Keys -join ',')) 'Serial order is canonical'
    $withoutFreeze=$options.Clone();$withoutFreeze.Remove('FreezeCompiledInputs')
    Reject { & $matrix @withoutFreeze -PlanOnly } 'requires -FreezeCompiledInputs'
    Reject { & $matrix @options -PlanOnly -Profile GPU_NATIVE_DRAFT } 'ValidateSet|does not belong'
    Save-Manifest $entries[0..4]
    Reject { & $matrix @options -PlanOnly } 'exactly six'
    Save-Manifest (@($entries)+@{id='extra';independentExpectedRoot='extra'})
    Reject { & $matrix @options -PlanOnly } 'exactly six'
    $entries[0].id='unknown'
    Save-Manifest $entries
    Reject { & $matrix @options -PlanOnly } 'context ID'
    $entries[0].id='vanilla-nether'
    Save-Manifest $entries
    Reject { & $matrix @options -PlanOnly } 'context ID'
    $entries[0].id='vanilla-overworld'
    $entries[0].CaseFilter='.*';Save-Manifest $entries
    Reject { & $matrix @options -PlanOnly } 'Unknown manifest property'
    $entries[0].Remove('CaseFilter');Save-Manifest $entries
    $file=Join-Path $testRoot 'vanilla-overworld/seed-0-x-30-z-30-NOISE.snap'
    $bytes=[IO.File]::ReadAllBytes($file)
    $duplicateDir=Join-Path $testRoot 'vanilla-overworld/duplicate'
    New-Item -ItemType Directory -Path $duplicateDir | Out-Null
    $duplicate=Join-Path $duplicateDir ([IO.Path]::GetFileName($file))
    [IO.File]::WriteAllBytes($duplicate,$bytes)
    Reject { & $matrix @options -PlanOnly } 'Duplicate case'
    Remove-Item -LiteralPath $duplicate
    Remove-Item -LiteralPath $file
    Reject { & $matrix @options -PlanOnly } 'Missing cases'
    [IO.File]::WriteAllBytes($file,$bytes)
    foreach ($replacement in @('seed-1-x-30-z-30-NOISE.snap','seed-0-x-0-z-30-NOISE.snap',
            'seed-9223372036854775808-x-30-z-30-NOISE.snap','seed-00-x-30-z-30-NOISE.snap','seed-0-x-30-z-30-FULL.snap')) {
        $wrong=Join-Path (Split-Path -Parent $file) $replacement
        Move-Item -LiteralPath $file -Destination $wrong
        Reject { & $matrix @options -PlanOnly } 'Wrong seed/position|Wrong NOISE'
        Move-Item -LiteralPath $wrong -Destination $file
    }
    $text=[Text.Encoding]::UTF8.GetString($bytes)
    foreach ($badText in @($text.Replace('value=dimension=minecraft:overworld','value=dimension=minecraft:the_end'),
            $text.Replace('value=endpoint=NOISE','value=endpoint=FULL'),$text.Replace('value=seed=0','value=seed=1'),
            $text.Replace('ORIGINAL/','CANDIDATE/'),$text.Replace('WORLDGENNEXT-SNAPSHOT-1','WRONG'),
            ($text + "`nvalue=dimension=minecraft:overworld"))) {
        [IO.File]::WriteAllText($file,$badText)
        Reject { & $matrix @options -PlanOnly } 'header|identity|snapshot'
    }
    [IO.File]::WriteAllBytes($file,$bytes)
    $entries[1].independentExpectedRoot='vanilla-overworld';Save-Manifest $entries
    Reject { & $matrix @options -PlanOnly } 'context path overlap'
    $entries[1].independentExpectedRoot='vanilla-nether';Save-Manifest $entries
    $nested=$options.Clone();$nested.RunRoot=Join-Path $options.OutputRoot 'nested'
    Reject { & $matrix @nested -PlanOnly } 'path overlap'
    $nested=$options.Clone();$nested.OutputRoot=Join-Path $testRoot 'vanilla-overworld/output'
    Reject { & $matrix @nested -PlanOnly } 'path overlap'
    $nested=$options.Clone();$nested.OutputRoot=Join-Path (Split-Path -Parent $testRoot) ([IO.Path]::GetFileName($testRoot)+'-ancestor')
    $nested.RunRoot=Join-Path $nested.OutputRoot 'child'
    Reject { & $matrix @nested -PlanOnly } 'path overlap'
    New-Item -ItemType Directory -Path $options.OutputRoot | Out-Null
    Reject { & $matrix @options -PlanOnly } 'Fresh root'
    Remove-Item -LiteralPath $options.OutputRoot
    New-Item -ItemType Directory -Path $options.RunRoot | Out-Null
    Reject { & $matrix @options -PlanOnly } 'Fresh root'
    Remove-Item -LiteralPath $options.RunRoot
    $mods=Join-Path $testRoot 'fake-mods';New-Item -ItemType Directory -Path $mods | Out-Null
    $entries[3].modDirectory='fake-mods';Save-Manifest $entries
    Reject { & $matrix @options -PlanOnly } 'no jars'
    [IO.File]::WriteAllText((Join-Path $mods 'fixture.jar'),'not a real jar')
    $forward=& $matrix @options -PlanOnly -SharedStages -SharedEndIsland -EnablePipelineOptimization `
        -BatchElements 16383 -MaxShaderSourceChars 1500000 -TimeoutMinutesPerGroup 8 `
        -TimeoutMinutesPerContext 42 -MatrixDeadlineMinutes 200
    foreach ($context in $forward.contexts) {
        $a=$context.arguments
        Assert ($a.FreezeCompiledInputs -and $a.SharedStages -and $a.SharedEndIsland -and $a.EnablePipelineOptimization -and
            $a.BatchElements -eq 16383 -and $a.MaxShaderSourceChars -eq 1500000 -and $a.TimeoutMinutesPerGroup -eq 8) 'Existing child flag forwarding'
        Assert (-not $a.Contains('Profile') -and -not $a.Contains('CaseFilter')) 'No invented child flags'
    }
    Assert ($forward.contexts[3].arguments.ModDirectory -eq $mods -and $forward.timeoutMinutesPerContext -eq 42 -and
        $forward.matrixDeadlineMinutes -eq 200) 'Mod path and outer deadline forwarding'

    # Load function definitions only; never run the executable entrypoint without PlanOnly.
    $tokens=$null;$parseErrors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($matrix,[ref]$tokens,[ref]$parseErrors)
    Assert ($parseErrors.Count -eq 0) 'Matrix parses cleanly'
    foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $context=$plan.contexts[0]
    New-Item -ItemType Directory -Path $context.arguments.OutputRoot | Out-Null
    New-Item -ItemType Directory -Path $context.arguments.RunRoot | Out-Null
    $frozenPath=Join-Path $context.arguments.RunRoot 'frozen-compiled-inputs.tsv'
    [IO.File]::WriteAllText($frozenPath,"fake frozen inputs`n",[Text.UTF8Encoding]::new($false))
    $fixtureSha=(Get-FileHash -LiteralPath $frozenPath -Algorithm SHA256).Hash
    $rows=@()
    foreach ($case in $context.cases) {
        $artifact=Join-Path $context.arguments.OutputRoot ($case.relativeCase+'.chunk')
        [IO.File]::WriteAllText($artifact,'fake chunk')
        $r=@{status='PASS';route='GPU_IEEE_BITS';resultAbi='chunk-result-v4';compilerVersion='worldgennext-gpu-live-v0.2'
            comparedBlocks=7;mismatches=0;shaderHash='fake';spirvHash='fake';device=@{name='FAKE_NOT_GPU'}}
        [IO.File]::WriteAllText(($artifact+'.gpu-receipt.json'),($r | ConvertTo-Json))
        $rows+=@{case=$case.relativeCase.Replace('\','/');expected=$case.expected;actual=$artifact
            receipt=($artifact+'.gpu-receipt.json');comparisonExitCode=0;comparedBlocks=7}
    }
    $fakeReport=@{kind='worldgennext_gpu_noise_replay';status='PASS';route='GPU_IEEE_BITS';frozenCompiledInputs=$true
        compiledInputsSha256=$fixtureSha;completeCoverage=$true;selectedSubset=$false;independentOracle=$true
        mismatches=0;comparedCases=250;comparedFields=2500;cases=$rows}
    $reportPath=Join-Path $context.arguments.OutputRoot 'replay-report.json'
    [IO.File]::WriteAllText($reportPath,($fakeReport | ConvertTo-Json -Depth 5))
    $metrics=Measure-MatrixContext $context $fixtureSha
    Assert ($metrics.passedReceipts -eq 250 -and $metrics.comparedBlocks -eq 1750 -and $metrics.comparedFields -eq 2500) 'Count actual fake artifacts'
    $metrics=Measure-MatrixContext $context 'OTHER_SHA'
    Assert ($metrics.passedReceipts -eq 0 -and $metrics.comparedFields -eq 0 -and $metrics.error) 'Mixed frozen snapshot cannot count receipts or independent fields'
    $fakeReport.compiledInputsSha256='OTHER_SHA'
    [IO.File]::WriteAllText($reportPath,($fakeReport | ConvertTo-Json -Depth 5))
    Assert ((Measure-MatrixContext $context $fixtureSha).comparedCases -eq 0) 'Final report SHA must bind same child snapshot'
    $fakeReport.compiledInputsSha256=$fixtureSha
    $fakeReport.cases[0].comparisonExitCode=$null
    [IO.File]::WriteAllText($reportPath,($fakeReport | ConvertTo-Json -Depth 5))
    Assert ((Measure-MatrixContext $context $fixtureSha).comparedCases -eq 0) 'Missing comparison cannot mean zero mismatches'
    $fakeReport.cases[0].comparisonExitCode=0
    $fakeReport.cases[0].expected='wrong'
    [IO.File]::WriteAllText($reportPath,($fakeReport | ConvertTo-Json -Depth 5))
    Assert ((Measure-MatrixContext $context $fixtureSha).comparedCases -eq 0) 'Case must bind original artifact'

    # Mock only the process and artifact boundary. These tests cannot launch a real child.
    function Get-FrozenCompiledInputsSha {
        if ($script:mode -eq 'drift' -and $script:calls.Count -gt 0) { 'CHANGED_SHA' } else { 'FAKE_SHA' }
    }
    $script:calls=@();$script:active=0;$script:mode='failure';$MatrixDeadlineMinutes=100;$TimeoutMinutesPerContext=10
    function Invoke-MatrixChild($context,[double]$minutes) {
        Assert ($script:active -eq 0) 'No concurrent children'
        $script:active++;$script:calls+= $context.id;$script:active--
        $status=if ($script:mode -eq 'failure' -and $script:calls.Count -eq 2) { 'FAIL' }
            elseif ($script:mode -eq 'timeout' -and $script:calls.Count -eq 2) { 'TIMEOUT' } else { 'COMPLETED' }
        [pscustomobject]@{status=$status;exitCode=$(if ($status -eq 'COMPLETED') {0} else {37})
            workerExited=($script:mode -ne 'unowned');error=$null;elapsedMillis=1}
    }
    function Measure-MatrixContext($context,[string]$sha) {
        $count=if ($script:mode -in @('failure','timeout') -and $script:calls.Count -eq 2) {0} else {250}
        [pscustomobject]@{passedReceipts=$count;comparedBlocks=($count*7);comparedCases=$count;comparedFields=($count*10)
            childFrozenCompiledInputsSha=$sha;error=$null}
    }
    foreach ($mode in @('failure','timeout','unowned','drift','deadline','success')) {
        $script:mode=$mode;$script:calls=@()
        $MatrixDeadlineMinutes=if ($mode -eq 'deadline') {0} else {100}
        $p=$plan | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $p.outputRoot=Join-Path $testRoot "mock-output-$mode";$p.runRoot=Join-Path $testRoot "mock-run-$mode"
        $report=Invoke-SerialMatrix $p
        Assert (-not $report.qualification) 'Even complete isolated matrix never claims G12'
        if ($mode -eq 'success') {
            Assert ($report.completeCoverage -and $report.status -eq 'PASS' -and $report.comparedCases -eq 1500 -and
                $report.passedReceipts -eq 1500 -and $report.comparedBlocks -eq 10500 -and $report.comparedFields -eq 15000) 'Complete mocked counters'
        } elseif ($mode -eq 'deadline') {
            Assert ($script:calls.Count -eq 0 -and $report.status -eq 'TIMEOUT' -and $report.comparedCases -eq 0) 'Expired deadline launches no child'
        } elseif ($mode -in @('unowned','drift')) {
            Assert ($script:calls.Count -eq 1 -and @($report.contexts | Where-Object status -eq 'NOT_RUN').Count -eq 5) 'Unproven exit stops all later children'
            Assert ($report.comparedCases -eq 0 -and $report.comparedFields -eq 0) 'Unproven exit or changed snapshot cannot count fields'
        } else {
            Assert ($script:calls.Count -eq 6 -and ($script:calls -join ',') -ceq ($dimensions.Keys -join ',')) 'Failure collected serially with no retry'
            Assert ($report.contexts[1].process.exitCode -eq 37 -and $report.contexts[1].status -eq $(if ($mode -eq 'timeout') {'TIMEOUT'} else {'FAIL'})) 'Preserve child failure/timeout and exit code'
            Assert ($report.comparedCases -eq 1250 -and $report.comparedFields -eq 12500) 'Coverage counts only actual mocked passes'
        }
    }
    Write-Host "PASS $checks driver-free GPU matrix contract assertions; no Gradle/Java/GPU/Minecraft launches. Synthetic fixtures are not GPU evidence."
} finally {
    # Delete only this test's unique, verified temp tree, including fake artifacts.
    $resolved=[IO.Path]::GetFullPath($testRoot)
    $tempPrefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'worldgennext-matrix-contract-*') { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

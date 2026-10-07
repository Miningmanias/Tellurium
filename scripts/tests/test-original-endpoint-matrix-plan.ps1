# Read-only planning/rejections: no capture, child process, Gradle or native calls.
$ErrorActionPreference='Stop'
$runner=Join-Path $PSScriptRoot '../capture-original-endpoint-matrix.ps1'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('tellurium-original-matrix-plan-'+[guid]::NewGuid())
$capture=Join-Path $fixture 'captures';$runs=Join-Path $fixture 'runs';$checks=0
function Check([bool]$condition,[string]$message){if(-not $condition){throw $message};$script:checks++}
function Reject([scriptblock]$action,[string]$message){$failed=$false;try{& $action|Out-Null}catch{$failed=$true};Check $failed $message}
try{
    $p=& $runner -CaptureRoot $capture -RunRoot $runs -Endpoint FULL -RepeatOriginal -FreezeCompiledInputs -PlanOnly
    Check ($p.expectedCases -eq 216 -and $p.expectedRepeatCases -eq 216 -and $p.expectedReopenedCases -eq 0) 'FULL exact two-seed denominator'
    Check ($p.status -eq 'PLAN_ONLY' -and -not $p.releaseQualification -and -not $p.productionHookEnabled) 'plan denies execution/qualification'
    Check (-not(Test-Path -LiteralPath $fixture)) 'planning writes nothing'
    $p=& $runner -CaptureRoot $capture -RunRoot $runs -Endpoint SAVED -FreezeCompiledInputs -PlanOnly
    Check ($p.expectedCases -eq 216 -and $p.expectedReopenedCases -eq 216 -and $p.expectedRepeatCases -eq 0) 'SAVED separate reopened denominator'
    $p=& $runner -CaptureRoot $capture -RunRoot $runs -Endpoint NOISE -FreezeCompiledInputs -PlanOnly
    Check ($p.expectedCases -eq 1500 -and $p.contexts.Count -eq 6) 'NOISE full signed-seed denominator'
    Reject {& $runner -CaptureRoot $capture -RunRoot $runs -PlanOnly} 'freeze is mandatory'
    Reject {& $runner -CaptureRoot $capture -RunRoot $capture -FreezeCompiledInputs -PlanOnly} 'identical roots fail'
    Reject {& $runner -CaptureRoot $capture -RunRoot (Join-Path $capture 'child') -FreezeCompiledInputs -PlanOnly} 'nested roots fail'
    New-Item -ItemType Directory -Path $fixture|Out-Null
    $manifest=Join-Path $fixture 'contexts.json'
    $inputManifest=Join-Path $repo 'test-manifest/v0.2-gpu-matrix-local.json'
    $json=Get-Content -LiteralPath $inputManifest -Raw|ConvertFrom-Json
    foreach($c in $json.contexts){if($c.modDirectory){$c.modDirectory=[IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $inputManifest) $c.modDirectory))}}
    $json.contexts[5].id='vanilla-overworld';$json|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $manifest
    Reject {& $runner -ContextManifest $manifest -CaptureRoot $capture -RunRoot $runs -FreezeCompiledInputs -PlanOnly} 'duplicate context fails'
    $json.contexts[5].id='combined-overworld';$json.contexts[5].modDirectory=$json.contexts[3].modDirectory
    $json|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $manifest
    Reject {& $runner -ContextManifest $manifest -CaptureRoot $capture -RunRoot $runs -FreezeCompiledInputs -PlanOnly} 'incorrect pinned combined stack fails'
    Check (-not(Test-Path -LiteralPath $capture) -and -not(Test-Path -LiteralPath $runs)) 'rejections create no capture/run artifacts'
    Write-Host "PASS original endpoint matrix MODEL_PLAN assertions=$checks (no game/native/child execution)"
}finally{
    $absolute=[IO.Path]::GetFullPath($fixture);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if(-not $absolute.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($absolute) -notlike 'tellurium-original-matrix-plan-*'){throw 'Unsafe fixture cleanup path'}
    if(Test-Path -LiteralPath $absolute){Remove-Item -LiteralPath $absolute -Recurse -Force}
}

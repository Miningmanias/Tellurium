$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot '../capture-original-corpus.ps1'
$checks = 0
function Check([bool]$value, [string]$message) {
    if (-not $value) { throw $message }
    $script:checks++
}
function Reject([scriptblock]$operation, [string]$message) {
    $rejected = $false
    try { & $operation | Out-Null } catch { $rejected = $true }
    Check $rejected $message
}
$fresh = Join-Path ([IO.Path]::GetTempPath()) ('tellurium-oracle-plan-' + [guid]::NewGuid())
$capture = Join-Path $fresh 'captures'
$runs = Join-Path $fresh 'runs'
$p = & $runner -Endpoint FULL -Seeds @('0','12345') -CoreSquareSideChunks 3 `
    -CaptureRoot $capture -RunRoot $runs -PlanOnly
Check ($p.caseCount -eq 36 -and $p.endpoint -eq 'FULL') 'FULL release subset has 36 cores/context'
Check ($p.execution -eq 'NOT_RUN' -and -not $p.releaseQualification) 'planning is not execution'
$p = & $runner -CoreSquareSideChunks 5 -CaptureRoot $capture -RunRoot $runs -PlanOnly
Check ($p.caseCount -eq 250 -and $p.seeds.Count -eq 5) 'NOISE defaults retain all five signed seeds'
Check (-not (Test-Path -LiteralPath $fresh)) 'plan creates no files/directories'
$reopened=Join-Path $fresh 'reopened'
$p=& $runner -Endpoint SAVED -Seeds @('0','12345') -CoreSquareSideChunks 3 `
    -CaptureRoot $capture -ReopenedCaptureRoot $reopened -RunRoot $runs -Reopen -FreezeCompiledInputs -PlanOnly
Check ($p.caseCount -eq 36 -and $p.reopen -and $p.frozenCompiledInputs -and $p.reopenedCaptureRoot -eq $reopened) 'SAVED plan binds fresh reopen and freeze'
Reject {& $runner -Endpoint FULL -CaptureRoot $capture -RunRoot $runs -Reopen -ReopenedCaptureRoot $reopened -PlanOnly} 'FULL cannot request reopen'
Reject {& $runner -Endpoint SAVED -CaptureRoot $capture -RunRoot $runs -Reopen -PlanOnly} 'SAVED reopen needs its own root'
Reject {& $runner -Endpoint SAVED -CaptureRoot $capture -RunRoot $runs -ReopenedCaptureRoot $reopened -PlanOnly} 'reopened root needs reopen'
Reject {& $runner -Endpoint SAVED -CaptureRoot $capture -RunRoot $runs -Reopen -ReopenedCaptureRoot $runs -PlanOnly} 'reopen cannot reuse run root'
Reject {& $runner -Endpoint SAVED -CaptureRoot $capture -RunRoot $runs -Reopen -ReopenedCaptureRoot (Join-Path $capture 'child') -PlanOnly} 'reopen cannot nest under capture'
foreach ($bad in @('9223372036854775808','-9223372036854775809','00','+1',' 1','1.0','')) {
    Reject { & $runner -Seeds @($bad) -CaptureRoot $capture -RunRoot $runs -PlanOnly } "reject noncanonical seed '$bad'"
}
Reject { & $runner -Seeds @('0','0') -CaptureRoot $capture -RunRoot $runs -PlanOnly } 'reject duplicate seeds'
Reject { & $runner -Seeds @() -CaptureRoot $capture -RunRoot $runs -PlanOnly } 'reject empty seeds'
Reject { & $runner -CoreSquareSideChunks 2 -CaptureRoot $capture -RunRoot $runs -PlanOnly } 'reject even grids'
Reject { & $runner -Endpoint UNKNOWN -CaptureRoot $capture -RunRoot $runs -PlanOnly } 'reject unknown endpoints'
Reject { & $runner -CaptureRoot $capture -RunRoot $capture -PlanOnly } 'reject overlapping roots'
Check (-not (Test-Path -LiteralPath $fresh)) 'rejections create no artifacts'
Write-Host "PASS original capture planning assertions=$checks (no game/native execution)"

# Serial reference live NOISE/FULL/SAVED campaign. Never admits production.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$MatrixManifest,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$RunRoot,
    [ValidateSet('NOISE','FULL','SAVED')][string]$Endpoint = 'FULL',
    [ValidateSet('CPU_OWNED','GPU_IEEE_BITS')][string]$Backend = 'GPU_IEEE_BITS',
    [ValidateRange(0,50)][int]$BatchSize = 0,
    [ValidateRange(1,180)][int]$TimeoutMinutesPerBatch = 25,
    [ValidateRange(1,180)][int]$TimeoutMinutesPerContext = 150,
    [ValidateRange(1,8640)][int]$MatrixDeadlineMinutes = 720,
    [ValidateRange(1,16384)][int]$GpuBatchElements = 16383,
    [switch]$FreezeCompiledInputs,
    [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Logical matrix requires PowerShell 7.' }
if (-not $FreezeCompiledInputs) { throw 'Logical matrix requires -FreezeCompiledInputs.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'LogicalEndpointMatrix.ps1')
. (Join-Path $PSScriptRoot 'CompiledReplayInputs.ps1')
. (Join-Path $PSScriptRoot 'Invoke-ReplayProcess.ps1')
$Endpoint = $Endpoint.ToUpperInvariant(); $Backend = $Backend.ToUpperInvariant()
if ($BatchSize -eq 0) { $BatchSize = if ($Endpoint -eq 'NOISE') { 25 } else { 9 } }
$plan = New-LogicalEndpointMatrixPlan -ManifestPath $MatrixManifest -Endpoint $Endpoint -Backend $Backend `
    -OutputRoot $OutputRoot -RunRoot $RunRoot
$plan | Add-Member NoteProperty batchSize $BatchSize
if ($PlanOnly) { return $plan }
$frozen = Get-WorldgenCompiledInputSnapshot $repo
$plan.status = 'RUNNING'
$plan | Add-Member NoteProperty compiledInputsSha256 $frozen.Hash
$plan | Add-Member NoteProperty matrixDeadlineMinutes $MatrixDeadlineMinutes
$plan | Add-Member NoteProperty timeoutMinutesPerBatch $TimeoutMinutesPerBatch
$plan | Add-Member NoteProperty timeoutMinutesPerContext $TimeoutMinutesPerContext
New-Item -ItemType Directory -Path $plan.outputRoot,$plan.runRoot | Out-Null
[IO.File]::WriteAllText((Join-Path $plan.runRoot 'frozen-compiled-inputs.tsv'), $frozen.Manifest, [Text.UTF8Encoding]::new($false))
$plan | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $plan.outputRoot 'matrix-plan.json') -Encoding UTF8
$results = [Collections.Generic.List[object]]::new()
$timer = [Diagnostics.Stopwatch]::StartNew()
$stopReason = $null
$runner = Join-Path $PSScriptRoot 'replay-minecraft-logical.ps1'
$shell = (Get-Process -Id $PID).Path
$quote = { param([string]$value) "'" + $value.Replace("'", "''") + "'" }
function Write-LogicalMatrixProgress([string]$status) {
    $compared=0; $fields=0; $reopened=0; $reopenedFields=0; $published=0; $states=0L
    foreach ($row in $results) {
        if ($row.status -ne 'PASS') { continue }
        $compared += $row.evidence.comparedCases; $fields += $row.evidence.comparedFields
        $reopened += $row.evidence.reopenedComparedCases; $reopenedFields += $row.evidence.reopenedComparedFields
        $published += $row.evidence.gpuCorePublications; $states += $row.evidence.gpuCommittedStorageStates
    }
    $report = [ordered]@{
        schemaVersion=1; kind='tellurium_logical_endpoint_matrix'; status=$status; endpoint=$Endpoint; route=$Backend
        coordinatorDispatch='INLINE_REFERENCE'; frozenCompiledInputs=$true; compiledInputsSha256=$frozen.Hash
        expectedCases=$plan.expectedCases; comparedCases=$compared; comparedFields=$fields
        expectedReopenedCases=$plan.expectedReopenedCases; reopenedComparedCases=$reopened; reopenedComparedFields=$reopenedFields
        gpuCorePublications=$published; gpuCommittedStorageStates=$states
        completeCoverage=($status -eq 'PASS_COMPLETE_REFERENCE_MATRIX')
        productionHookEnabled=$false; releaseQualification=$false; elapsedMillis=$timer.ElapsedMilliseconds
        failure=$stopReason; contexts=$results.ToArray()
        note='Compared core counts only. Halo work, process success and this reference matrix do not qualify production threading, lifecycle faults, installed-product behavior, TPS or G12.'
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $plan.outputRoot 'logical-matrix-report.json') -Encoding UTF8
}
Write-LogicalMatrixProgress 'RUNNING'
foreach ($context in $plan.contexts) {
    $result=[pscustomobject]@{id=$context.id; status='NOT_RUN'; expectedCases=$context.cases.Count
        processLog=(Join-Path (Join-Path $plan.runRoot $context.id) 'runner-process'); evidence=$null; error=$stopReason}
    if (-not $stopReason) {
        try {
            Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
            Assert-LogicalMatrixInputHashes $context
            $remaining=($MatrixDeadlineMinutes*60)-$timer.Elapsed.TotalSeconds
            if ($remaining -le 0) { throw 'Matrix deadline exhausted before context dispatch.' }
            $budget=[int][Math]::Max(1,[Math]::Floor([Math]::Min($TimeoutMinutesPerContext*60,$remaining)))
            $arguments=[ordered]@{Endpoint=$Endpoint; Backend=$Backend; ExpectedRoot=$context.expectedRoot
                OutputRoot=$context.outputRoot; RunRoot=$context.runRoot; BatchSize=$BatchSize
                TimeoutMinutesPerBatch=$TimeoutMinutesPerBatch; GpuBatchElements=$GpuBatchElements}
            if ($context.modDirectory) { $arguments.ModDirectory=$context.modDirectory }
            if ($Endpoint -eq 'SAVED') { $arguments.ReopenedExpectedRoot=$context.reopenedExpectedRoot }
            $code='$ErrorActionPreference = ''Stop''; try { & ' + (& $quote $runner)
            foreach ($key in $arguments.Keys) { $code += ' -' + $key + ' ' + (& $quote ([string]$arguments[$key])) }
            $code += ' -FreezeCompiledInputs'
            if ($Endpoint -eq 'SAVED') { $code += ' -Reopen' }
            $code += '; exit 0 } catch { Write-Error $_ -ErrorAction Continue; exit 1 }'
            $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($code))
            Write-Host "Logical matrix $Backend/$Endpoint context=$($context.id) cores=$($context.cases.Count) timeoutSeconds=$budget"
            Invoke-ReplayProcess -Command $shell -Arguments @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded) `
                -WorkingDirectory $repo -LogDirectory $result.processLog -TimeoutSeconds $budget
            Assert-WorldgenCompiledInputSnapshot $repo $frozen.Hash
            $result.evidence=Assert-LogicalEndpointContextReport -Context $context -Endpoint $Endpoint `
                -Backend $Backend -CompiledSha256 $frozen.Hash
            $result.status='PASS'
        } catch {
            $stopReason=$_.Exception.Message; $result.status='FAIL'; $result.error=$stopReason
        }
    }
    $results.Add($result)
    Write-LogicalMatrixProgress $(if ($stopReason) { 'FAIL' } else { 'RUNNING' })
}
$timer.Stop()
if (-not $stopReason -and @($results | Where-Object { $_.status -eq 'PASS' }).Count -ne 6) { $stopReason='Incomplete context coverage.' }
Write-LogicalMatrixProgress $(if ($stopReason) { 'FAIL' } else { 'PASS_COMPLETE_REFERENCE_MATRIX' })
if ($stopReason) { throw "Logical matrix failed: $stopReason" }
Write-Host "PASS reference logical matrix backend=$Backend endpoint=$Endpoint cores=$($plan.expectedCases) (NOT_G12)"

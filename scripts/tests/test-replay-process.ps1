# Starts only tiny, owned PowerShell workers. No Gradle, Java, Minecraft or GPU.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../Invoke-ReplayProcess.ps1')
$taskRoot = Join-Path ([IO.Path]::GetTempPath()) ('worldgennext-owned-process-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $taskRoot | Out-Null
$taskExecutable = (Get-Process -Id $PID).Path
$taskChecks = 0
function Check($condition, [string]$message) {
    if (-not $condition) { throw "Assertion failed: $message" }
    $script:taskChecks++
}
try {
    $success = Join-Path $taskRoot 'success'
    Invoke-ReplayProcess -Command $taskExecutable -Arguments @('-NoProfile', '-Command', 'Write-Output "literal $notEvaluated"; exit 0') `
        -WorkingDirectory $taskRoot -LogDirectory $success -TimeoutSeconds 5
    $status = Get-Content -LiteralPath (Join-Path $success 'process-status.json') -Raw | ConvertFrom-Json
    Check ($status.status -eq 'PROCESS_COMPLETED' -and $status.workerExited -and $status.exitCode -eq 0) 'Success binds an exited worker'
    Check (-not $status.processTreeKillAttempted) 'Success needs no process-tree kill'
    $failed = $false
    try {
        Invoke-ReplayProcess -Command $taskExecutable -Arguments @('-NoProfile', '-Command', 'exit 0') `
            -WorkingDirectory $taskRoot -LogDirectory $success -TimeoutSeconds 5
    } catch { $failed = $_.Exception.Message -like '*Refusing to overwrite*' }
    Check $failed 'Evidence reuse fails before launch'
    $nonzero = Join-Path $taskRoot 'nonzero'
    $failed = $false
    try {
        Invoke-ReplayProcess -Command $taskExecutable -Arguments @('-NoProfile', '-Command', 'exit 7') `
            -WorkingDirectory $taskRoot -LogDirectory $nonzero -TimeoutSeconds 5
    } catch { $failed = $true }
    $status = Get-Content -LiteralPath (Join-Path $nonzero 'process-status.json') -Raw | ConvertFrom-Json
    Check ($failed -and $status.status -eq 'PROCESS_FAILED' -and $status.exitCode -eq 7 -and $status.workerExited) 'Nonzero is failure, not generation success'
    $timeout = Join-Path $taskRoot 'timeout'
    $failed = $false
    try {
        Invoke-ReplayProcess -Command $taskExecutable -Arguments @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30; exit 0') `
            -WorkingDirectory $taskRoot -LogDirectory $timeout -TimeoutSeconds 1
    } catch { $failed = $true }
    $status = Get-Content -LiteralPath (Join-Path $timeout 'process-status.json') -Raw | ConvertFrom-Json
    Check ($failed -and $status.status -eq 'TIMEOUT' -and $status.timedOut) 'Deadline stays failed after cleanup'
    Check ($status.workerExited -and $status.processTreeKillAttempted -and $status.processTreeKillExitCode -eq 0) 'Only owned worker tree terminated'
    Check (-not (Get-Process -Id $status.ownedWorkerPid -ErrorAction SilentlyContinue)) 'Timed-out worker not left live'
    Write-Host "PASS owned replay process contracts: $taskChecks assertions"
} finally {
    $resolved = [IO.Path]::GetFullPath($taskRoot)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if (-not $resolved.StartsWith($tempParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notlike 'worldgennext-owned-process-*') {
        throw "Refusing unexpected temporary cleanup target: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

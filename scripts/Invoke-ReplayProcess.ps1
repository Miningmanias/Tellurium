# Bounded owned-process execution. This helper itself never starts a game or GPU.
function Invoke-ReplayProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$LogDirectory,
        [ValidateRange(1, 10800)][int]$TimeoutSeconds = 1500
    )
    $ErrorActionPreference = 'Stop'
    $directory = [IO.Path]::GetFullPath($LogDirectory)
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $stdout = Join-Path $directory 'process-stdout.log'
    $stderr = Join-Path $directory 'process-stderr.log'
    $exitRecord = Join-Path $directory 'process-exit-code.txt'
    $statusFile = Join-Path $directory 'process-status.json'
    foreach ($path in @($stdout, $stderr, $exitRecord, $statusFile)) {
        if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite process evidence: $path" }
    }
    $quote = { param([string]$value) "'" + $value.Replace("'", "''") + "'" }
    $worker = '& ' + (& $quote ([IO.Path]::GetFullPath($Command))) + ' ' +
        (($Arguments | ForEach-Object { & $quote $_ }) -join ' ') + "`n" +
        '$replayExit = $LASTEXITCODE' + "`n" +
        '[IO.File]::WriteAllText(' + (& $quote $exitRecord) + ', [string]$replayExit)' + "`n" +
        'exit $replayExit'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($worker))
    $process = $null
    $ownedPid = $null
    $exitCode = $null
    $exited = $false
    $timedOut = $false
    $killAttempted = $false
    $killExitCode = $null
    $launchError = $null
    $cleanupError = $null
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $process = Start-Process -FilePath (Get-Process -Id $PID).Path `
            -ArgumentList "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded" `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $ownedPid = $process.Id
        while (-not $process.WaitForExit(1000)) {
            if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
                $process.Refresh()
                if (-not $process.HasExited) {
                    $timedOut = $true
                    $killAttempted = $true
                    & (Join-Path $env:SystemRoot 'System32/taskkill.exe') /PID $ownedPid /T /F | Out-Null
                    $killExitCode = $LASTEXITCODE
                    [void]$process.WaitForExit(15000)
                }
                break
            }
        }
        $process.Refresh()
        $exited = $process.HasExited
        if ($exited) { $process.WaitForExit(); $exitCode = $process.ExitCode }
    } catch {
        $launchError = $_.Exception.Message
        if ($null -ne $process) {
            try {
                $process.Refresh()
                if (-not $process.HasExited) {
                    $killAttempted = $true
                    & (Join-Path $env:SystemRoot 'System32/taskkill.exe') /PID $ownedPid /T /F | Out-Null
                    $killExitCode = $LASTEXITCODE
                    [void]$process.WaitForExit(15000)
                }
                $process.Refresh()
                $exited = $process.HasExited
                if ($exited) { $process.WaitForExit(); $exitCode = $process.ExitCode }
            } catch { $cleanupError = $_.Exception.Message }
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        $timer.Stop()
    }
    if ($null -eq $exitCode -and (Test-Path -LiteralPath $exitRecord)) {
        $record = (Get-Content -LiteralPath $exitRecord -Raw).Trim()
        if ($record -match '^-?\d+$') { $exitCode = [int]$record }
    }
    $status = [ordered]@{
        schemaVersion = 1
        kind = 'worldgennext_owned_replay_process'
        status = if ($timedOut) { 'TIMEOUT' } elseif ($exited -and $exitCode -eq 0 -and -not $launchError) { 'PROCESS_COMPLETED' } else { 'PROCESS_FAILED' }
        ownedWorkerPid = $ownedPid
        workerExited = $exited
        exitCode = $exitCode
        elapsedMillis = $timer.ElapsedMilliseconds
        timeoutSeconds = $TimeoutSeconds
        timedOut = $timedOut
        processTreeKillAttempted = $killAttempted
        processTreeKillExitCode = $killExitCode
        launchError = $launchError
        cleanupError = $cleanupError
        stdoutLog = $stdout
        stderrLog = $stderr
        note = 'Process completion is not artifact, parity, native lifecycle or release qualification.'
    }
    $status | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statusFile -Encoding UTF8
    if ($status.status -ne 'PROCESS_COMPLETED') {
        throw "Owned replay process ended $($status.status); workerExited=$exited; see $statusFile"
    }
    Write-Host "Process completed: elapsedMillis=$($timer.ElapsedMilliseconds) logs=$directory"
}

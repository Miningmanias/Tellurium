param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory,
    [string]$ServerJar = '',
    [string]$ModJar = '',
    [string]$RunRoot = '',
    [string]$JavaHome = '',
    [ValidateSet('CPU_ONLY', 'GPU_REQUIRED', 'AUTO_SUPPORTED')]
    [string[]]$Modes = @('CPU_ONLY', 'GPU_REQUIRED', 'AUTO_SUPPORTED'),
    [int]$StartupTimeoutSeconds = 180,
    [int]$ShutdownTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Resolve-FullPath([string]$Path, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw "$Name is required" }
    return [IO.Path]::GetFullPath($Path)
}

function Resolve-Java([string]$Requested) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Requested)) { $candidates += $Requested }
    elseif (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates += $env:JAVA_HOME }
    else { $candidates += 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot' }
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { continue }
        $release = Join-Path $candidate 'release'
        if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { continue }
        if (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="21[.\"]' -Quiet) { return (Resolve-Path -LiteralPath $java).Path }
    }
    throw 'JDK 21 was not found. Pass -JavaHome with its installation directory.'
}

function Assert-EmptyWorldTemplate([string]$Path) {
    $eula = Join-Path $Path 'eula.txt'
    $hasEula = Test-Path -LiteralPath $eula -PathType Leaf
    $eulaText = if ($hasEula) { Get-Content -LiteralPath $eula -Raw } else { '' }
    if (-not $hasEula -or $eulaText -notmatch '(?im)^\s*eula\s*=\s*true\s*$') {
        throw "Server template must contain eula=true in $eula"
    }
    foreach ($name in @('world', 'world_nether', 'world_the_end')) {
        if (Test-Path -LiteralPath (Join-Path $Path $name)) {
            throw "Server template contains an existing world directory '$name'. Use a disposable NeoForge template without generated worlds."
        }
    }
    $mods = Join-Path $Path 'mods'
    if (Test-Path -LiteralPath $mods) {
        $existing = @(Get-ChildItem -LiteralPath $mods -Filter 'worldgennext*.jar' -File)
        if ($existing.Count -gt 0) {
            throw "Server template already contains a WorldgenNext jar: $($existing[0].FullName). Remove it from the template rather than overwriting it."
        }
    }
}

function Find-ServerJar([string]$Directory, [string]$Requested) {
    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $path = [IO.Path]::GetFullPath((Join-Path $Directory $Requested))
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Server jar was not found: $path" }
        return $path
    }
    $standard = Join-Path $Directory 'server.jar'
    if (Test-Path -LiteralPath $standard -PathType Leaf) { return (Resolve-Path -LiteralPath $standard).Path }
    throw "No server.jar found in $Directory. Pass -ServerJar with a jar relative to -ServerDirectory."
}

function New-Config([string]$Directory, [string]$Mode) {
    $config = Join-Path $Directory 'config'
    New-Item -ItemType Directory -Force -Path $config | Out-Null
    @(
        "mode=$Mode"
        'hostBudgetBytes=268435456'
        'nativeBudgetBytes=268435456'
        'queueCapacity=64'
        'requestTimeoutMillis=30000'
        'enableQualifiedHook=false'
    ) | Set-Content -LiteralPath (Join-Path $config 'worldgennext.properties') -Encoding ASCII
}

function Stop-InstalledServer([Diagnostics.Process]$Process) {
    if ($null -eq $Process) { return }
    try { if ($Process.HasExited) { return } } catch { return }
    # Windows PowerShell 5.1 uses the .NET Framework Process type, which has
    # neither ArgumentList nor Kill(bool).  taskkill /T is the compatible
    # process-tree operation; the parameterless Kill is the final fallback.
    try {
        $taskkill = Join-Path $env:SystemRoot 'System32\taskkill.exe'
        if (Test-Path -LiteralPath $taskkill -PathType Leaf) {
            & $taskkill /PID ([string]$Process.Id) /T /F 2>$null | Out-Null
        }
    } catch { }
    try { if (-not $Process.HasExited) { $Process.Kill() } } catch { }
}

function Start-InstalledServer([string]$Java, [string]$Directory, [string]$Jar, [string]$Mode,
                               [int]$StartupTimeout, [int]$ShutdownTimeout) {
    $logs = Join-Path $Directory 'worldgennext-installed-test'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $stdoutPath = Join-Path $logs 'stdout.log'
    $stderrPath = Join-Path $logs 'stderr.log'
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Java
    $psi.WorkingDirectory = $Directory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $jarName = Split-Path -Leaf $Jar
    if ($jarName.Contains('"')) { throw "Server jar filename contains an unsupported quote: $jarName" }
    $psi.Arguments = '-jar "' + $jarName + '" --nogui'

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    $stdout = [IO.StreamWriter]::new($stdoutPath, $false)
    $stderr = [IO.StreamWriter]::new($stderrPath, $false)
    $stdout.AutoFlush = $true
    $stderr.AutoFlush = $true
    $outHandler = [Diagnostics.DataReceivedEventHandler]{ param($sender, $event)
        if ($null -ne $event.Data) { $stdout.WriteLine($event.Data) }
    }
    $errHandler = [Diagnostics.DataReceivedEventHandler]{ param($sender, $event)
        if ($null -ne $event.Data) { $stderr.WriteLine($event.Data) }
    }
    $process.add_OutputDataReceived($outHandler)
    $process.add_ErrorDataReceived($errHandler)
    try {
        if (-not $process.Start()) { throw "Could not start installed server for $Mode" }
        $process.BeginOutputReadLine()
        $process.BeginErrorReadLine()
        $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeout)
        $ready = $false
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            $latest = Join-Path $Directory 'logs\latest.log'
            if (Test-Path -LiteralPath $latest) {
                $text = Get-Content -LiteralPath $latest -Raw -ErrorAction SilentlyContinue
                if ($text -match 'WorldgenNext 0\.2\.0 loaded: mode=' -and $text -match 'Done \(') {
                    $ready = $true
                    break
                }
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) {
            if ($process.HasExited) { throw "Installed server exited before readiness for $Mode (exit=$($process.ExitCode))" }
            throw "Installed server did not reach readiness within ${StartupTimeout}s for $Mode"
        }

        $latestLog = Get-Content -LiteralPath (Join-Path $Directory 'logs\latest.log') -Raw
        if ($latestLog -notmatch ("WorldgenNext 0\.2\.0 loaded: mode=" + [regex]::Escape($Mode))) {
            throw "Installed server log did not report requested mode $Mode"
        }
        if ($Mode -eq 'CPU_ONLY' -and $latestLog -notmatch 'native=DISABLED') {
            throw 'CPU_ONLY installed smoke did not report native=DISABLED'
        }
        if ($Mode -eq 'CPU_ONLY' -and $latestLog -notmatch 'native=DISABLED.*live generation hook remains disabled') {
            throw 'CPU_ONLY installed smoke did not preserve the disabled-hook diagnostic'
        }

        $process.StandardInput.WriteLine('stop')
        $process.StandardInput.Flush()
        $shutdownDeadline = [DateTime]::UtcNow.AddSeconds($ShutdownTimeout)
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $shutdownDeadline) { Start-Sleep -Milliseconds 500 }
        if (-not $process.HasExited) {
            Stop-InstalledServer $process
            throw "Installed server did not shut down within ${ShutdownTimeout}s for $Mode"
        }
        if ($process.ExitCode -ne 0) { throw "Installed server exited with code $($process.ExitCode) for $Mode" }
        return [ordered]@{ mode = $Mode; status = 'PASS'; runDirectory = $Directory; stdout = $stdoutPath; stderr = $stderrPath; minecraftLog = (Join-Path $Directory 'logs\latest.log') }
    } finally {
        if (-not $process.HasExited) {
            Stop-InstalledServer $process
        }
        $stdout.Dispose()
        $stderr.Dispose()
        $process.Dispose()
    }
}

$source = Resolve-FullPath $ServerDirectory 'ServerDirectory'
if (-not (Test-Path -LiteralPath $source -PathType Container)) { throw "ServerDirectory is not a directory: $source" }
Assert-EmptyWorldTemplate $source
$serverJarPath = Find-ServerJar $source $ServerJar
$javaPath = Resolve-Java $JavaHome
if ([string]::IsNullOrWhiteSpace($ModJar)) { $ModJar = Join-Path $repoRoot 'neoforge-1211\build\libs\worldgennext-neoforge-1.21.1-0.2.0.jar' }
$modJarPath = Resolve-FullPath $ModJar 'ModJar'
if (-not (Test-Path -LiteralPath $modJarPath -PathType Leaf)) { throw "Mod jar was not found: $modJarPath" }

if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRoot ('build\installed-mod-tests\' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$runRootPath = [IO.Path]::GetFullPath($RunRoot)
$runRootInsideTemplate = $runRootPath.Equals($source, [StringComparison]::OrdinalIgnoreCase) -or $runRootPath.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
if ($runRootInsideTemplate) {
    throw 'RunRoot must not be the server template or a directory inside it'
}
if (Test-Path -LiteralPath $runRootPath) { throw "Refusing to reuse installed-test run root: $runRootPath" }
New-Item -ItemType Directory -Force -Path $runRootPath | Out-Null

if ($Modes.Count -eq 0 -or ($Modes | Select-Object -Unique).Count -ne $Modes.Count) {
    throw 'At least one unique installed-test mode is required'
}
$results = [System.Collections.Generic.List[object]]::new()
foreach ($mode in $Modes) {
    $modeDirectory = Join-Path $runRootPath $mode
    New-Item -ItemType Directory -Force -Path $modeDirectory | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $modeDirectory -Recurse -Force
    $modeJar = Join-Path $modeDirectory (Split-Path -Leaf $serverJarPath)
    if (-not (Test-Path -LiteralPath $modeJar -PathType Leaf)) {
        Copy-Item -LiteralPath $serverJarPath -Destination $modeJar
    }
    $modsDirectory = Join-Path $modeDirectory 'mods'
    New-Item -ItemType Directory -Force -Path $modsDirectory | Out-Null
    $stagedMod = Join-Path $modsDirectory (Split-Path -Leaf $modJarPath)
    if (Test-Path -LiteralPath $stagedMod) { throw "Refusing to overwrite staged mod: $stagedMod" }
    Copy-Item -LiteralPath $modJarPath -Destination $stagedMod
    New-Config $modeDirectory $mode
    Write-Host "Installed WorldgenNext smoke mode=$mode run=$modeDirectory"
    $results.Add((Start-InstalledServer $javaPath $modeDirectory $modeJar $mode $StartupTimeoutSeconds $ShutdownTimeoutSeconds))
}

$reportPath = Join-Path $runRootPath 'installed-mod-report.json'
[ordered]@{
    schemaVersion = 1
    kind = 'worldgennext_installed_mod_smoke'
    status = 'PASS'
    java = $javaPath
    serverDirectory = $source
    serverJar = $serverJarPath
    modJar = $modJarPath
    modes = $results
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "PASS installed WorldgenNext smoke modes=$($results.Count) report=$reportPath"

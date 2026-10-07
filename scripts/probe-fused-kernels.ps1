param(
    [string]$Source = 'build/fast-src/overworld.comp',
    [string]$Kernels = 'K_HEIGHT,K_COLUMN,K_CORNER,K_BLOCK,K_AQUIFER',
    [int]$TimeoutSeconds = 240,
    [string]$Defines = ''
)
# Compiles each fused kernel in its own process with a hard timeout; reports shaderc/driver times.
$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$sourcePath = (Resolve-Path (Join-Path $repo $Source)).Path
& (Join-Path $repo 'gradlew.bat') ':runtime-vulkan:classes' '--no-daemon' '-q' | Out-Null
$cp = (& (Join-Path $repo 'gradlew.bat') ':runtime-vulkan:printProbeClasspath' '--no-daemon' '-q' 2>$null | Select-Object -Last 1)
foreach ($k in ($Kernels -split ',')) {
    $log = Join-Path $repo "build/fast-src/probe-$k.log"
    $argList = @('-Dorg.lwjgl.system.stackSize=16384', '-cp', $cp, 'dev.tellurium.runtime.vulkan.fused.FusedCompileProbe', $sourcePath, $k) + ($Defines -split ' ' | Where-Object { $_ })
    $p = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList $argList -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -NoNewWindow
    if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
        $peak = [math]::Round($p.PeakWorkingSet64 / 1GB, 2)
        Stop-Process -Id $p.Id -Force
        Write-Host "$k : TIMEOUT after $TimeoutSeconds s (peak ${peak} GB)"
    } else {
        Write-Host "$k : $((Get-Content $log) -join ' | ')"
    }
}

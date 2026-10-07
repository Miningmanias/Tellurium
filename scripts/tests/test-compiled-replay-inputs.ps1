$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../CompiledReplayInputs.ps1')
$root = Join-Path ([IO.Path]::GetTempPath()) ('tellurium-compiled-input-contract-' + [Guid]::NewGuid().ToString('N'))
$modules = @('semantic-core','compiler-jvm','compiler-vulkan','material-codec','spatial-data',
        'chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')
$checks = 0
function Check([bool]$value, [string]$reason) {
    if (-not $value) { throw "Compiled-input contract failed: $reason" }
    $script:checks++
}
function Reject([scriptblock]$action, [string]$reason) {
    $rejected = $false
    try { & $action | Out-Null } catch { $rejected = $true }
    Check $rejected $reason
}
try {
    foreach ($module in $modules) {
        # The mod's reference target is built under mod/targets; the library modules at the root.
        $moduleHome = if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module }
        $directory = Join-Path $root "$moduleHome/build/classes/java/main"
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        [IO.File]::WriteAllBytes((Join-Path $directory 'Fixture.class'), [byte[]]@(1,2,3))
    }
    $first = Get-WorldgenCompiledInputSnapshot $root
    Check ($first.FileCount -eq 9) 'all nine modules covered'
    Check ($first.Hash -match '^[A-F0-9]{64}$') 'canonical SHA256'
    Check ($first.Manifest.EndsWith("`n") -and -not $first.Manifest.Contains("`r")) 'canonical newline'
    Check ($first.Hash -eq (Get-WorldgenCompiledInputSnapshot $root).Hash) 'stable fingerprint'
    Assert-WorldgenCompiledInputSnapshot $root $first.Hash
    $checks++
    $fixture = Join-Path $root 'semantic-core/build/classes/java/main/Fixture.class'
    [IO.File]::WriteAllBytes($fixture, [byte[]]@(1,2,4))
    Reject { Assert-WorldgenCompiledInputSnapshot $root $first.Hash } 'same-size bytecode drift rejected'
    [IO.File]::WriteAllBytes($fixture, [byte[]]@(1,2,3))
    $resources = Join-Path $root 'mod/targets/neoforge-1211/build/resources/main'
    New-Item -ItemType Directory -Path $resources -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $resources 'metadata.json'), '{}')
    $withResource = Get-WorldgenCompiledInputSnapshot $root
    Check ($withResource.FileCount -eq 10 -and $withResource.Hash -ne $first.Hash) 'resource inputs covered'
    [IO.File]::WriteAllText((Join-Path $resources 'metadata.json'), '[]')
    Reject { Assert-WorldgenCompiledInputSnapshot $root $withResource.Hash } 'same-size resource drift rejected'
    Reject { Assert-WorldgenCompiledInputSnapshot $root 'invalid-hash' } 'malformed expected hash rejected'
    Remove-Item -LiteralPath $fixture
    Reject { Get-WorldgenCompiledInputSnapshot $root } 'missing compiled module rejected'
    $tokens = $null; $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../replay-minecraft-logical.ps1'), [ref]$tokens, [ref]$errors)
    Check ($errors.Count -eq 0) 'logical runner parses'
    Write-Host "PASS compiled-replay-input synthetic assertions=$checks (no game/native execution)"
} finally {
    # This exact freshly-created fixture tree is disposable, never a repository path.
    $resolved = [IO.Path]::GetFullPath($root)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notlike 'tellurium-compiled-input-contract-*') {
        throw 'Refusing to delete a fixture tree outside its exact temporary parent'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

# Shared opt-in compiled-input binding; hashing is not a source or release qualification.
function Get-WorldgenCompiledInputSnapshot {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    $root = (Resolve-Path -LiteralPath $RepositoryRoot -ErrorAction Stop).Path
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($module in @('semantic-core','compiler-jvm','compiler-vulkan','material-codec',
            'spatial-data','chunk-engine','frontend-mc1211','runtime-vulkan','neoforge-1211')) {
        $classes = Join-Path $root "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/classes/java/main"
        if (-not (Test-Path -LiteralPath $classes -PathType Container) -or
                @(Get-ChildItem -LiteralPath $classes -File -Recurse -Filter '*.class').Count -eq 0) {
            throw "Frozen replay requires existing compiled classes: $classes"
        }
        foreach ($kind in @('classes/java/main','resources/main')) {
            $directory = Join-Path $root "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/$kind"
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -File -Recurse)) {
                if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw "Compiled replay input must not be a reparse point: $($file.FullName)"
                }
                $relative = [IO.Path]::GetRelativePath($directory, $file.FullName).Replace('\','/')
                if ($relative -match '[\t\r\n]') { throw 'Compiled replay path contains manifest control characters' }
                $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                $lines.Add("$module/$kind/$relative`t$($file.Length)`t$hash")
            }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    $manifest = ($lines -join "`n") + "`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($manifest))) }
    finally { $sha.Dispose() }
    return [pscustomobject]@{ Manifest = $manifest; Hash = $hash; FileCount = $lines.Count }
}

function Assert-WorldgenCompiledInputSnapshot {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot,
          [Parameter(Mandatory = $true)][string]$ExpectedHash)
    if ($ExpectedHash -notmatch '^[0-9A-Fa-f]{64}$' -or
            (Get-WorldgenCompiledInputSnapshot $RepositoryRoot).Hash -ne $ExpectedHash) {
        throw 'Frozen compiled classes/resources changed during replay; refusing mixed-snapshot success.'
    }
}

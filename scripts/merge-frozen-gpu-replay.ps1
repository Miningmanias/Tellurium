param(
    [Parameter(Mandatory)][string]$ExpectedRoot,
    [Parameter(Mandatory)][string[]]$CandidateRoots,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$FrozenCompiledManifest
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Frozen subset merging requires PowerShell 7 (pwsh).' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$expected = (Resolve-Path -LiteralPath $ExpectedRoot).Path
$sources = @($CandidateRoots | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
$output = [IO.Path]::GetFullPath($OutputRoot)
if (Test-Path -LiteralPath $output) { throw 'Merge output must be a fresh directory.' }
function Is-SameOrChild([string]$path, [string]$parent) {
    $parent = $parent.TrimEnd('\', '/')
    return $path.Equals($parent, [StringComparison]::OrdinalIgnoreCase) -or
        $path.StartsWith($parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}
foreach ($protected in @($expected) + $sources) {
    if ((Is-SameOrChild $output $protected) -or (Is-SameOrChild $protected $output)) {
        throw 'Merge output must not overlap inputs.'
    }
}
$modules = @('semantic-core', 'compiler-jvm', 'compiler-vulkan', 'material-codec',
    'spatial-data', 'chunk-engine', 'frontend-mc1211', 'runtime-vulkan', 'neoforge-1211')
$manifest = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $FrozenCompiledManifest).Path)
function Current-Manifest {
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($module in $modules) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/classes/java/main"))) {
            throw "Missing frozen classes: $module"
        }
        foreach ($kind in @('classes/java/main', 'resources/main')) {
            $directory = Join-Path $repoRoot "$(if ($module -eq 'neoforge-1211') { 'mod/targets/neoforge-1211' } else { $module })/build/$kind"
            if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            foreach ($file in @(Get-ChildItem -LiteralPath $directory -File -Recurse)) {
                $relative = [IO.Path]::GetRelativePath($directory, $file.FullName).Replace('\', '/')
                $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
                $lines.Add("$module/$kind/$relative`t$($file.Length)`t$hash")
            }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    return ($lines -join "`n") + "`n"
}
function Assert-Frozen {
    if ((Current-Manifest) -cne $manifest) { throw 'Compiled input manifest changed; mixed snapshots are not admitted.' }
}
Assert-Frozen
$captures = @(Get-ChildItem -LiteralPath $expected -Filter '*.snap' -File -Recurse)
if ($captures.Count -eq 0) { throw 'Independent expected subset is empty.' }
$admitted = [System.Collections.Generic.Dictionary[string, object]]::new([StringComparer]::OrdinalIgnoreCase)
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($root in $sources) {
    foreach ($receiptPath in @(Get-ChildItem -LiteralPath $root -Filter '*.chunk.gpu-receipt.json' -File -Recurse)) {
        $receipt = Get-Content -LiteralPath $receiptPath.FullName -Raw | ConvertFrom-Json
        if ($receipt.status -ne 'PASS' -or $receipt.route -ne 'GPU_IEEE_BITS' -or
                $receipt.resultAbi -ne 'chunk-result-v4' -or $receipt.compilerVersion -ne 'worldgennext-gpu-live-v0.2' -or
                $receipt.comparedBlocks -le 0 -or $receipt.mismatches -ne 0 -or
                [string]::IsNullOrWhiteSpace($receipt.shaderHash) -or [string]::IsNullOrWhiteSpace($receipt.spirvHash) -or
                $null -eq $receipt.device) { throw "Rejected GPU receipt: $($receiptPath.FullName)" }
        $artifact = $receiptPath.FullName.Substring(0, $receiptPath.FullName.Length - '.gpu-receipt.json'.Length)
        $relative = [IO.Path]::GetRelativePath($root, $artifact)
        $capture = Join-Path $expected ([IO.Path]::ChangeExtension($relative, '.snap'))
        if (-not (Test-Path -LiteralPath $capture -PathType Leaf)) { throw "Unexpected candidate: $relative" }
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf) -or
                (Get-Item -LiteralPath $artifact).Length -eq 0 -or
                -not (Test-Path -LiteralPath ($artifact + '.candidate-status') -PathType Leaf) -or
                (Get-Content -LiteralPath ($artifact + '.candidate-status') -TotalCount 1) -ne 'PASS') {
            throw "Incomplete committed candidate artifact: $artifact"
        }
        if ($admitted.ContainsKey($relative)) { throw "Duplicate case: $relative" }
        $admitted.Add($relative, @{artifact=$artifact; receipt=$receiptPath.FullName})
        $rows.Add([ordered]@{case=$relative.Replace('\','/'); sourceRoot=$root; comparedBlocks=$receipt.comparedBlocks;
            mismatches=0; shaderHash=$receipt.shaderHash; spirvHash=$receipt.spirvHash; device=$receipt.device.name})
    }
}
if ($admitted.Count -ne $captures.Count) { throw "Incomplete merge: $($admitted.Count) candidates / $($captures.Count) expected." }
[IO.Directory]::CreateDirectory($output) | Out-Null
foreach ($entry in $admitted.GetEnumerator()) {
    $destination = Join-Path $output $entry.Key
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    Copy-Item -LiteralPath $entry.Value.artifact -Destination $destination
    Copy-Item -LiteralPath $entry.Value.receipt -Destination ($destination + '.gpu-receipt.json')
    Copy-Item -LiteralPath ($entry.Value.artifact + '.candidate-status') -Destination ($destination + '.candidate-status')
}
$skip = @()
foreach ($module in $modules) { $skip += @('-x', ":${module}:compileJava", '-x', ":${module}:processResources") }
$quoted = [char]34
$arguments = "compare-candidate-corpus $quoted$($expected.Replace('\','/'))$quoted $quoted$($output.Replace('\','/'))$quoted"
& (Join-Path $repoRoot 'gradlew.bat') ':oracle-and-replay:run' "--args=$arguments" '--no-daemon' @skip
if ($LASTEXITCODE -ne 0) { throw 'Independent merged corpus comparison failed.' }
Assert-Frozen
$sha = [Security.Cryptography.SHA256]::Create()
try { $compiledHash = [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($manifest))) }
finally { $sha.Dispose() }
$blockCount = 0L
foreach ($row in $rows) { $blockCount += [long]$row['comparedBlocks'] }
$report = [ordered]@{schemaVersion=1; kind='worldgennext_frozen_gpu_subset_merge'; status='PASS';
    route='GPU_IEEE_BITS'; independentOracle=$true; expectedDirectory=$expected; candidateDirectory=$output;
    sourceDirectories=$sources; compiledInputsSha256=$compiledHash;
    compiledInputsScope='recovery_and_comparator_inputs'; sourceGenerationFingerprintsComplete=$false;
    comparedCases=$rows.Count; comparedFields=$rows.Count*10; comparedBlocks=$blockCount;
    mismatches=0; completeExpectedSubset=$true; releaseQualification=$false; cases=$rows}
[IO.File]::WriteAllText((Join-Path $output 'merged-replay-report.json'), ($report | ConvertTo-Json -Depth 7), [Text.UTF8Encoding]::new($false))
Write-Host "PASS frozen GPU subset merge cases=$($rows.Count) fields=$($rows.Count*10) report=$output/merged-replay-report.json"

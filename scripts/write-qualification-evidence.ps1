param(
    [Parameter(Mandatory = $true)]
    [string]$ComparisonReport,
    [string]$SourceArtifact = '',
    [Parameter(Mandatory = $true)]
    [string]$ContextKey,
    [ValidateSet('CPU_OWNED', 'GPU_IEEE_BITS')]
    [string]$Route = 'CPU_OWNED',
    [string]$ResultAbi = 'chunk-result-v4',
    [string]$CompilerVersion = '',
    [string]$OutputFile = '',
    [switch]$IndependentOracle
)

$ErrorActionPreference = 'Stop'

function Resolve-RegularFile([string]$path, [string]$label) {
    $resolved = [IO.Path]::GetFullPath($path)
    $item = Get-Item -LiteralPath $resolved -ErrorAction Stop
    if ($item.PSIsContainer -or [IO.File]::GetAttributes($resolved).HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw "$label must be a regular non-reparse file: $resolved"
    }
    return $resolved
}

function Escape-Property([string]$value) {
    $builder = [Text.StringBuilder]::new()
    for ($index = 0; $index -lt $value.Length; $index++) {
        $character = $value[$index]
        switch ([int][char]$character) {
            92 { [void]$builder.Append('\\') }
            9 { [void]$builder.Append('\t') }
            10 { [void]$builder.Append('\n') }
            13 { [void]$builder.Append('\r') }
            default {
                if ($character -eq ' ' -or $character -eq '#' -or $character -eq '!' -or
                    $character -eq '=' -or $character -eq ':') {
                    [void]$builder.Append('\')
                }
                [void]$builder.Append($character)
            }
        }
    }
    return $builder.ToString()
}

if ([string]::IsNullOrWhiteSpace($ContextKey)) { throw 'ContextKey must be non-blank' }
$reportPath = Resolve-RegularFile $ComparisonReport 'ComparisonReport'
if ([string]::IsNullOrWhiteSpace($SourceArtifact)) { $SourceArtifact = $reportPath }
$sourcePath = Resolve-RegularFile $SourceArtifact 'SourceArtifact'
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path (Split-Path -Parent $reportPath) 'qualification.properties'
}
$outputPath = [IO.Path]::GetFullPath($OutputFile)
$outputParent = [IO.Path]::GetDirectoryName($outputPath)
if ([string]::IsNullOrWhiteSpace($outputParent)) { throw "OutputFile has no parent directory: $outputPath" }
if (Test-Path -LiteralPath $outputPath) { throw "Refusing to overwrite qualification receipt: $outputPath" }
if (-not $sourcePath.StartsWith($outputParent.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "SourceArtifact must be inside the receipt directory: $sourcePath"
}

$report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($report.status -ne 'PASS') { throw "Comparison report is not PASS: $reportPath" }
if ($null -ne $report.route -and $report.route -ne $Route) {
    throw "Comparison report route $($report.route) does not match requested route $Route"
}
if ($null -eq $report.comparedCases -or [long]$report.comparedCases -lt 1500) {
    throw "Qualification receipt requires at least 1500 compared cases"
}
if ($null -eq $report.comparedFields -or [long]$report.comparedFields -le 0) {
    throw 'Comparison report has no compared field count'
}
if ($null -eq $report.mismatches -or [long]$report.mismatches -ne 0) {
    throw 'Comparison report contains mismatches'
}
if ($null -eq $report.completeCoverage -or -not [bool]$report.completeCoverage) {
    throw 'Comparison report does not prove complete coverage'
}
if (-not $IndependentOracle) {
    throw 'Pass -IndependentOracle only after verifying the expected corpus came from a separate original-only process'
}
if ([string]::IsNullOrWhiteSpace($ResultAbi)) { throw 'ResultAbi must be non-blank' }
if ([string]::IsNullOrWhiteSpace($CompilerVersion)) {
    $CompilerVersion = if ($Route -eq 'CPU_OWNED') { 'worldgennext-cpu-live-v0.2' } else { 'worldgennext-gpu-live-v0.2' }
}

$relativeSource = [IO.Path]::GetRelativePath($outputParent, $sourcePath).Replace('\', '/')
if ($relativeSource.StartsWith('../') -or $relativeSource.StartsWith('..\') -or [IO.Path]::IsPathRooted($relativeSource)) {
    throw "SourceArtifact escaped the receipt directory: $relativeSource"
}
$sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
$lines = [string[]]@(
    'schemaVersion=1',
    "contextKey=$(Escape-Property $ContextKey)",
    "route=$(Escape-Property $Route)",
    "resultAbi=$(Escape-Property $ResultAbi)",
    "compilerVersion=$(Escape-Property $CompilerVersion)",
    "comparedCases=$([long]$report.comparedCases)",
    "comparedFields=$([long]$report.comparedFields)",
    "mismatches=$([long]$report.mismatches)",
    'completeCoverage=true',
    'independentOracle=true',
    "sourceArtifactSha256=$sourceHash",
    "sourceArtifactFile=$(Escape-Property $relativeSource)"
)
New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
[IO.File]::WriteAllLines($outputPath, $lines, [Text.UTF8Encoding]::new($false))
Write-Host "PASS qualification receipt=$outputPath source=$sourcePath cases=$([long]$report.comparedCases) fields=$([long]$report.comparedFields) route=$Route"

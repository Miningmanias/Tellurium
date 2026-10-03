param(
    [Parameter(Mandatory = $true)]
    [string]$InputManifest,
    [string]$OutputFile = '',
    [ValidateSet('CPU_OWNED', 'GPU_IEEE_BITS')]
    [string]$Route = 'CPU_OWNED',
    [string]$ResultAbi = 'chunk-result-v4',
    [string]$CompilerVersion = '',
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

function Resolve-ManifestInput([string]$value, [string]$label, [string]$baseDirectory) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw "$label must be non-blank" }
    $path = if ([IO.Path]::IsPathRooted($value)) { $value } else { Join-Path $baseDirectory $value }
    return Resolve-RegularFile $path $label
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

function Append-Property([Text.StringBuilder]$builder, [string]$key, [string]$value) {
    [void]$builder.Append($key).Append('=').Append((Escape-Property $value)).Append("`n")
}

function Relative-InDirectory([string]$parent, [string]$child, [string]$label) {
    $relative = [IO.Path]::GetRelativePath($parent, $child).Replace('\', '/')
    if ([IO.Path]::IsPathRooted($relative) -or $relative -eq '..' -or $relative.StartsWith('../')) {
        throw "$label must be inside the receipt directory: $child"
    }
    return $relative
}

if (-not $IndependentOracle) {
    throw 'Pass -IndependentOracle only after verifying every report came from a separate original-only process'
}
if ([string]::IsNullOrWhiteSpace($ResultAbi)) { throw 'ResultAbi must be non-blank' }
if ([string]::IsNullOrWhiteSpace($CompilerVersion)) {
    $CompilerVersion = if ($Route -eq 'CPU_OWNED') { 'worldgennext-cpu-live-v0.2' } else { 'worldgennext-gpu-live-v0.2' }
}

$manifestPath = Resolve-RegularFile $InputManifest 'InputManifest'
$manifestDirectory = Split-Path -Parent $manifestPath
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $manifestDirectory 'qualification-bundle.properties'
}
$outputPath = [IO.Path]::GetFullPath($OutputFile)
$outputParent = [IO.Path]::GetDirectoryName($outputPath)
if ([string]::IsNullOrWhiteSpace($outputParent)) { throw "OutputFile has no parent directory: $outputPath" }
if (Test-Path -LiteralPath $outputPath) { throw "Refusing to overwrite qualification bundle: $outputPath" }

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$entries = if ($null -ne $manifest.entries) { @($manifest.entries) } else { @($manifest) }
if ($entries.Count -eq 0 -or $entries.Count -gt 256) {
    throw 'InputManifest must contain 1..256 context entries'
}

$rows = [System.Collections.Generic.List[object]]::new()
$seenContexts = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$totalCases = [long]0
$totalFields = [long]0
$totalMismatches = [long]0
foreach ($entry in $entries) {
    if ($null -eq $entry) { throw 'InputManifest contains a null entry' }
    $context = [string]$entry.contextKey
    if ([string]::IsNullOrWhiteSpace($context)) { throw 'Every bundle entry requires contextKey' }
    if (-not $seenContexts.Add($context)) { throw "Duplicate qualification context: $context" }

    $reportPath = Resolve-ManifestInput ([string]$entry.comparisonReport) 'comparisonReport' $manifestDirectory
    $sourceValue = [string]$entry.sourceArtifact
    if ([string]::IsNullOrWhiteSpace($sourceValue)) { $sourceValue = $reportPath }
    $sourcePath = if ([IO.Path]::IsPathRooted($sourceValue)) {
        Resolve-RegularFile $sourceValue 'sourceArtifact'
    } else {
        Resolve-RegularFile (Join-Path $manifestDirectory $sourceValue) 'sourceArtifact'
    }

    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($report.status -ne 'PASS') { throw "Comparison report is not PASS: $reportPath" }
    if ($null -ne $report.route -and $report.route -ne $Route) {
        throw "Comparison report route $($report.route) does not match requested route $Route"
    }
    if ($null -eq $report.comparedCases -or [long]$report.comparedCases -le 0) {
        throw "Comparison report has no positive comparedCases: $reportPath"
    }
    if ($null -eq $report.comparedFields -or [long]$report.comparedFields -le 0) {
        throw "Comparison report has no positive comparedFields: $reportPath"
    }
    if ($null -eq $report.mismatches -or [long]$report.mismatches -ne 0) {
        throw "Comparison report contains mismatches: $reportPath"
    }
    if ($null -eq $report.completeCoverage -or -not [bool]$report.completeCoverage) {
        throw "Comparison report does not prove complete coverage: $reportPath"
    }

    $relativeSource = Relative-InDirectory $outputParent $sourcePath 'sourceArtifact'
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $cases = [long]$report.comparedCases
    $fields = [long]$report.comparedFields
    $mismatches = [long]$report.mismatches
    $totalCases += $cases
    $totalFields += $fields
    $totalMismatches += $mismatches
    $rows.Add([pscustomobject]@{
        ContextKey = $context
        Route = $Route
        ResultAbi = $ResultAbi
        CompilerVersion = $CompilerVersion
        ComparedCases = $cases
        ComparedFields = $fields
        Mismatches = $mismatches
        SourceHash = $sourceHash
        SourceFile = $relativeSource
    })
}

if ($totalCases -lt 1500) { throw "Qualification bundle requires at least 1500 aggregate compared cases; got $totalCases" }
if ($totalFields -le 0) { throw 'Qualification bundle has no aggregate compared fields' }
if ($totalMismatches -ne 0) { throw "Qualification bundle contains mismatches: $totalMismatches" }

$rows.Sort([System.Comparison[object]]{
    param($left, $right)
    return [StringComparer]::Ordinal.Compare([string]$left.ContextKey, [string]$right.ContextKey)
})

$content = [Text.StringBuilder]::new()
Append-Property $content 'schemaVersion' '2'
Append-Property $content 'entryCount' ([string]$rows.Count)
for ($index = 0; $index -lt $rows.Count; $index++) {
    $row = $rows[$index]
    $prefix = "entry.$index."
    Append-Property $content ($prefix + 'contextKey') $row.ContextKey
    Append-Property $content ($prefix + 'route') $row.Route
    Append-Property $content ($prefix + 'resultAbi') $row.ResultAbi
    Append-Property $content ($prefix + 'compilerVersion') $row.CompilerVersion
    Append-Property $content ($prefix + 'comparedCases') ([string]$row.ComparedCases)
    Append-Property $content ($prefix + 'comparedFields') ([string]$row.ComparedFields)
    Append-Property $content ($prefix + 'mismatches') ([string]$row.Mismatches)
    Append-Property $content ($prefix + 'completeCoverage') 'true'
    Append-Property $content ($prefix + 'independentOracle') 'true'
    Append-Property $content ($prefix + 'sourceArtifactSha256') $row.SourceHash
    Append-Property $content ($prefix + 'sourceArtifactFile') $row.SourceFile
}

New-Item -ItemType Directory -Force -Path $outputParent | Out-Null
$temporary = Join-Path $outputParent ('.' + [IO.Path]::GetFileName($outputPath) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::WriteAllText($temporary, $content.ToString(), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $outputPath)
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
}

Write-Host "PASS qualification bundle=$outputPath contexts=$($rows.Count) cases=$totalCases fields=$totalFields route=$Route"

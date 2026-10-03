param(
    [string]$JavaHome,
    [string[]]$Tasks = @('test','build','verifyArchitecture','testReport')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$candidates = @()
if ($JavaHome) { $candidates += $JavaHome }
elseif ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
if (-not $JavaHome -and $env:ProgramFiles) {
    $adoptium = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
    if (Test-Path -LiteralPath $adoptium) {
        $candidates += (Get-ChildItem -LiteralPath $adoptium -Directory -Filter 'jdk-21*').FullName
    }
}
$selectedJdk = $null
foreach ($candidate in $candidates) {
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $releaseFile) -and
        (Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="21[."]' -Quiet)) {
        $selectedJdk = $candidate
        break
    }
}
if (-not $selectedJdk) { throw 'JDK 21 was not found. Pass -JavaHome with its installation directory.' }
$previousJavaHome = $env:JAVA_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $selectedJdk
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --no-daemon
    $buildExit = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
exit $buildExit

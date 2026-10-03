param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
& .\gradlew.bat verifyV02Inputs testWorldgenSemantics testIntegerIeee testMinecraftOracle testChunkResultContract testWorldgenCoordinator checkReleaseJar --no-daemon
if ($LASTEXITCODE -ne 0) { throw "v0.2 prerequisite checks failed" }
Write-Host 'Prerequisite contract checks passed. G12 remains closed until real same-stack Minecraft/GPU/FULL/SAVED evidence is archived.'

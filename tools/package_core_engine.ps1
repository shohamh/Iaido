param([string]$OutputDirectory = "dist")
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& .\gradlew.bat :core-engine:jar --no-daemon
Copy-Item -LiteralPath "core-engine\build\libs\core-engine.jar" -Destination (Join-Path $OutputDirectory "core-engine.jar") -Force
Write-Output "Packaged core-engine.jar in $OutputDirectory"

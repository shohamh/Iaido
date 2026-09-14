param(
    [string]$OutputDirectory,
    [string]$MinAppVersion,
    [string]$DownloadUrl
)
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedOutput = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $repoRoot "dist"
} elseif ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutputDirectory))
}
$jarName = "core-engine.jar"
$jarSource = Join-Path $repoRoot "core-engine\build\libs\$jarName"
$jarTarget = Join-Path $resolvedOutput $jarName
$checksumTarget = Join-Path $resolvedOutput "$jarName.sha256"
$manifestTarget = Join-Path $resolvedOutput "core-engine-manifest.json"

$versionLine = Get-Content -LiteralPath (Join-Path $repoRoot "gradle.properties") |
    Where-Object { $_ -match "^ninjaKeysVersion=" } |
    Select-Object -First 1
if ($null -eq $versionLine) {
    throw "gradle.properties must define ninjaKeysVersion"
}
$version = ($versionLine -split "=", 2)[1].Trim()
if ([string]::IsNullOrWhiteSpace($version)) {
    throw "ninjaKeysVersion must not be empty"
}
if ($version -notmatch "^\d+\.\d+\.\d+$") {
    throw "ninjaKeysVersion must use MAJOR.MINOR.PATCH format"
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
    & .\gradlew.bat :core-engine:jar --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle core-engine JAR task failed with exit code $LASTEXITCODE"
    }
    if (-not (Test-Path -LiteralPath $jarSource)) {
        throw "Gradle did not produce $jarSource"
    }
    Copy-Item -LiteralPath $jarSource -Destination $jarTarget -Force
    $sha256 = (Get-FileHash -LiteralPath $jarTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    "$sha256  $jarName" | Set-Content -LiteralPath $checksumTarget -Encoding utf8
    [ordered]@{
        artifact = $jarName
        version = $version
        sha256 = $sha256
        minAppVersion = if ([string]::IsNullOrWhiteSpace($MinAppVersion)) { $version } else { $MinAppVersion }
        downloadUrl = if ($null -eq $DownloadUrl) { "" } else { $DownloadUrl }
        signature = ""
    } | ConvertTo-Json | Set-Content -LiteralPath $manifestTarget -Encoding utf8
    Write-Output "Packaged $jarName version $version in $resolvedOutput"
}
finally {
    Pop-Location
}

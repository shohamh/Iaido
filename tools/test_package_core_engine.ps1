$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("iaido-package-test-" + [Guid]::NewGuid().ToString("N"))
$firstOutput = Join-Path $temporaryRoot "first"
$secondOutput = Join-Path $temporaryRoot "second"
$packageScript = Join-Path $repoRoot "tools\package_core_engine.ps1"

function Assert-Condition([bool]$condition, [string]$message) {
    if (-not $condition) {
        throw $message
    }
}

try {
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    Push-Location $repoRoot

    & $packageScript -OutputDirectory $firstOutput
    & $packageScript -OutputDirectory $secondOutput

    $jarName = "core-engine.jar"
    foreach ($output in @($firstOutput, $secondOutput)) {
        $jar = Join-Path $output $jarName
        $checksum = Join-Path $output "$jarName.sha256"
        $manifest = Join-Path $output "core-engine-manifest.json"
        Assert-Condition (Test-Path -LiteralPath $jar) "Missing packaged JAR in $output"
        Assert-Condition (Test-Path -LiteralPath $checksum) "Missing checksum in $output"
        Assert-Condition (Test-Path -LiteralPath $manifest) "Missing manifest in $output"

        $actualHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        $recordedHash = (Get-Content -LiteralPath $checksum -Raw).Trim().Split([char]32)[0].ToLowerInvariant()
        Assert-Condition ($recordedHash -eq $actualHash) "Checksum does not match packaged JAR in $output"

        $manifestObject = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
        Assert-Condition ($manifestObject.artifact -eq $jarName) "Manifest artifact name is incorrect in $output"
        Assert-Condition ($manifestObject.sha256 -eq $actualHash) "Manifest checksum does not match packaged JAR in $output"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$manifestObject.version)) "Manifest version is missing in $output"
    }

    $firstHash = (Get-FileHash -LiteralPath (Join-Path $firstOutput $jarName) -Algorithm SHA256).Hash
    $secondHash = (Get-FileHash -LiteralPath (Join-Path $secondOutput $jarName) -Algorithm SHA256).Hash
    Assert-Condition ($firstHash -eq $secondHash) "Packaging is not reproducible: hashes differ"
    Write-Output "PASS: core-engine packaging is reproducible and self-checking"
}
finally {
    if ((Get-Location).Path -eq $repoRoot) {
        Pop-Location
    }
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

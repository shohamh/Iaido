[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [string]$Destination,

    [switch]$ExpectArtifacts
)

$ErrorActionPreference = "Stop"
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found on PATH"
}

& adb -s $DeviceSerial wait-for-device
if ($LASTEXITCODE -ne 0) {
    throw "adb did not reach device $DeviceSerial"
}

$remotePath = "/sdcard/Android/data/com.iaido.app/files/ime-e2e"
& adb -s $DeviceSerial shell test -d $remotePath
$remoteExists = $LASTEXITCODE -eq 0
if (-not $remoteExists) {
    if ($ExpectArtifacts) {
        Write-Warning "No IME artifact directory was available at $remotePath"
    }
    exit 0
}

$adbPath = (Get-Command adb -ErrorAction Stop).Source
$stdoutPath = [System.IO.Path]::GetTempFileName()
$stderrPath = [System.IO.Path]::GetTempFileName()
try {
    $pullProcess = Start-Process `
        -FilePath $adbPath `
        -ArgumentList @("-s", $DeviceSerial, "pull", $remotePath, $destinationPath) `
        -NoNewWindow `
        -PassThru `
        -Wait `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath
    $pullExitCode = $pullProcess.ExitCode
} finally {
    if (Test-Path -LiteralPath $stdoutPath) { Remove-Item -LiteralPath $stdoutPath -Force }
    if (Test-Path -LiteralPath $stderrPath) { Remove-Item -LiteralPath $stderrPath -Force }
}

if ($pullExitCode -ne 0) {
    if ($ExpectArtifacts) {
        throw "Could not collect IME artifacts from $remotePath (adb exit code $pullExitCode)"
    }
    Write-Warning "Could not collect IME artifacts from $remotePath (adb exit code $pullExitCode)"
} else {
    Write-Host "Collected IME artifacts from $remotePath."
}

exit 0

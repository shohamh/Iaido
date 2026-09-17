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

& adb -s $DeviceSerial pull $remotePath $destinationPath
if ($LASTEXITCODE -ne 0 -and $ExpectArtifacts) {
    Write-Warning "No IME artifact directory was available at $remotePath"
}

exit 0

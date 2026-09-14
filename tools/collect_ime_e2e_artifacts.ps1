[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DeviceSerial,

    [Parameter(Mandatory = $true)]
    [string]$Destination
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

$remotePath = "/sdcard/Android/data/com.ninjakeys.app/files/ime-e2e"
& adb -s $DeviceSerial pull $remotePath $destinationPath
if ($LASTEXITCODE -ne 0) {
    Write-Warning "No IME artifact directory was available at $remotePath"
}

exit 0

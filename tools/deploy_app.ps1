[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [switch]$Build,
    [switch]$Logcat
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb was not found on PATH. Pair the phone with Android Studio or adb first."
}

Push-Location $repoRoot
try {
    if ($Build) {
        & .\gradlew.bat :app:assembleDebug --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle debug APK task failed with exit code $LASTEXITCODE"
        }
    }
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "Debug APK not found at $apkPath. Pass -Build or assemble it first."
    }

    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        $connected = @((& adb devices) | Select-String "^\S+\s+device$")
        if ($connected.Count -ne 1) {
            throw "Specify -DeviceSerial when zero or multiple ADB devices are connected."
        }
        $DeviceSerial = ($connected[0].Line -split "\s+")[0]
    }

    & adb -s $DeviceSerial install -r $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw "ADB install failed for device $DeviceSerial"
    }
    Write-Output "Installed Iaido debug APK on $DeviceSerial"

    if ($Logcat) {
        & adb -s $DeviceSerial logcat -v time "Iaido:*" "*:S"
    }
}
finally {
    Pop-Location
}

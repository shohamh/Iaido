[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$TailscaleHost,
    [int]$TailscalePort = 5555,
    [string]$UsbDeviceSerial,
    [switch]$Build,
    [switch]$Logcat
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

$androidSdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
} else {
    $null
}
if ($androidSdkRoot) {
    $platformTools = Join-Path $androidSdkRoot "platform-tools"
    if (Test-Path -LiteralPath $platformTools) {
        $env:Path = "$platformTools;$env:Path"
    }
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb was not found on PATH or the standard Android SDK platform-tools directory. Install platform-tools or set ANDROID_SDK_ROOT."
}
if ($TailscaleHost -and $DeviceSerial) {
    throw "Use either -DeviceSerial or -TailscaleHost, not both."
}
if ($UsbDeviceSerial -and -not $TailscaleHost) {
    throw "-UsbDeviceSerial requires -TailscaleHost. Use -DeviceSerial for a direct USB install."
}
if ($UsbDeviceSerial -and $UsbDeviceSerial -eq $DeviceSerial) {
    throw "Use -UsbDeviceSerial only as the USB bootstrap target, not as -DeviceSerial."
}
if ($TailscalePort -lt 1 -or $TailscalePort -gt 65535) {
    throw "-TailscalePort must be between 1 and 65535."
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

    if ($TailscaleHost) {
        $tailscaleEndpoint = "$TailscaleHost`:$TailscalePort"
        if ($UsbDeviceSerial) {
            Write-Output "Enabling ADB TCP mode on USB device $UsbDeviceSerial (port $TailscalePort)"
            & adb -s $UsbDeviceSerial tcpip $TailscalePort
            if ($LASTEXITCODE -ne 0) {
                throw "Could not switch USB device $UsbDeviceSerial to ADB TCP mode on port $TailscalePort."
            }
            Start-Sleep -Seconds 2
        }
        Write-Output "Connecting ADB to Tailscale endpoint $tailscaleEndpoint"
        $connectOutput = @(& adb connect $tailscaleEndpoint 2>&1)
        $connectText = ($connectOutput -join "`n").Trim()
        if ($LASTEXITCODE -ne 0 -or $connectText -notmatch "(?i)(connected to|already connected to)") {
            throw "ADB could not connect to $tailscaleEndpoint. $connectText Ensure Tailscale and ADB TCP mode are enabled on the phone."
        }
        $DeviceSerial = $tailscaleEndpoint
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

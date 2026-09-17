[CmdletBinding()]
param(
    [string]$AvdName,
    [string]$DeviceSerial,
    [string]$Class,
    [switch]$KeepArtifacts
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gradle = Join-Path $root "gradlew.bat"
$debugApk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $root "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$artifactDestination = Join-Path $root "artifacts\ime-e2e"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found on PATH"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}
if (-not $DeviceSerial -and -not $AvdName) {
    throw "Specify -AvdName when starting an emulator or -DeviceSerial for an existing device"
}

function Get-OnlineDeviceSerials {
    @(& adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
}

function Wait-ForBootCompleted([string]$serial) {
    & adb -s $serial wait-for-device
    if ($LASTEXITCODE -ne 0) { throw "adb did not reach device $serial" }
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        $bootOutput = & adb -s $serial shell getprop sys.boot_completed 2>$null
        $boot = if ($null -eq $bootOutput) { "" } else { ($bootOutput -join "").Trim() }
        if ($boot -eq "1") { return }
        Start-Sleep -Seconds 2
    }
    throw "Device $serial did not finish booting"
}

$startedProcess = $null
if ($DeviceSerial) {
    Wait-ForBootCompleted $DeviceSerial
} else {
    $existing = Get-OnlineDeviceSerials
    if ($existing.Count -gt 1) {
        throw "Multiple adb devices are already connected; specify -DeviceSerial. Devices: $($existing -join ', ')"
    }
    if ($existing.Count -eq 1) {
        $DeviceSerial = $existing[0]
        Write-Warning "Using existing device $DeviceSerial; -AvdName was not started"
        Wait-ForBootCompleted $DeviceSerial
    } else {
        if (-not (Get-Command emulator -ErrorAction SilentlyContinue)) {
            throw "emulator was not found on PATH; start '$AvdName' manually or pass -DeviceSerial"
        }
        $startedProcess = Start-Process -FilePath emulator -ArgumentList @("-avd", $AvdName, "-no-audio", "-no-boot-anim") -WindowStyle Hidden -PassThru
        $DeviceSerial = "emulator-5554"
        Wait-ForBootCompleted $DeviceSerial
    }
}

$previousAndroidSerial = $env:ANDROID_SERIAL
$env:ANDROID_SERIAL = $DeviceSerial
$gradleExitCode = 1
try {
    & $gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $debugApk) -or -not (Test-Path -LiteralPath $testApk)) {
        throw "Expected debug APKs were not produced"
    }

    & adb -s $DeviceSerial install -r $debugApk
    if ($LASTEXITCODE -ne 0) { throw "Installing the debug APK failed" }
    & adb -s $DeviceSerial install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw "Installing the instrumentation APK failed" }

    $iaidoIme = "com.iaido.app/.IaidoInputMethodService"
    & adb -s $DeviceSerial shell ime enable $iaidoIme | Out-Host
    & adb -s $DeviceSerial shell ime set $iaidoIme | Out-Host

    $testArguments = @()
    if ($Class) {
        $testArguments = @("-Pandroid.testInstrumentationRunnerArguments.class=$Class")
    }
    & $gradle :app:connectedDebugAndroidTest @testArguments --no-daemon --console=plain
    $gradleExitCode = $LASTEXITCODE
} catch {
    Write-Error $_
    $gradleExitCode = 1
} finally {
    New-Item -ItemType Directory -Force -Path $artifactDestination | Out-Null
    if ($gradleExitCode -ne 0) {
        & (Join-Path $PSScriptRoot "collect_ime_e2e_artifacts.ps1") `
            -DeviceSerial $DeviceSerial `
            -Destination $artifactDestination `
            -ExpectArtifacts
    } else {
        & (Join-Path $PSScriptRoot "collect_ime_e2e_artifacts.ps1") `
            -DeviceSerial $DeviceSerial `
            -Destination $artifactDestination
    }
    if ($previousAndroidSerial) { $env:ANDROID_SERIAL = $previousAndroidSerial } else { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue }
    if ($startedProcess -and -not $KeepArtifacts) {
        Write-Host "Leaving emulator process $($startedProcess.Id) running; use emulator controls to stop it."
    }
}

exit $gradleExitCode

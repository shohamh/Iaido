[CmdletBinding()]
param(
    [string]$AvdName,
    [string]$DeviceSerial,
    [string]$Class,
    [switch]$KeepArtifacts,
    [switch]$InstallOnly,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$DirectInstrumentation,
    [switch]$GradleConnected
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gradle = Join-Path $root "gradlew.bat"
$debugApk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $root "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$installStatePath = Join-Path $root "app\build\.ime-e2e-install-state.json"
$artifactDestination = Join-Path $root "artifacts\ime-e2e"
$useDirectInstrumentation = -not $GradleConnected

# Android Studio often exposes these tools only to its own terminal. Make the runner
# reproducible from a normal PowerShell session as well.
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
    foreach ($toolDirectory in @((Join-Path $androidSdkRoot "platform-tools"), (Join-Path $androidSdkRoot "emulator"))) {
        if (Test-Path -LiteralPath $toolDirectory) {
            $env:Path = "$toolDirectory;$env:Path"
        }
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb was not found on PATH"
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle wrapper was not found at $gradle"
}
if (-not $DeviceSerial -and -not $AvdName) {
    throw "Specify -AvdName when starting an emulator or -DeviceSerial for an existing device"
}
if ($InstallOnly -and ($SkipInstall -or $DirectInstrumentation)) {
    throw "-InstallOnly cannot be combined with -SkipInstall or -DirectInstrumentation"
}
if ($DirectInstrumentation -and $GradleConnected) {
    throw "-DirectInstrumentation cannot be combined with -GradleConnected"
}
if ($SkipInstall -and -not $useDirectInstrumentation) {
    throw "-SkipInstall requires direct instrumentation; omit -GradleConnected"
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

function Get-ApkHash([string]$path) {
    (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}

function Test-InstalledPackage([string]$packageName) {
    $packagePath = & adb -s $DeviceSerial shell pm path $packageName 2>$null
    $LASTEXITCODE -eq 0 -and $packagePath -match '^package:'
}

function Test-InstallState([string]$debugHash, [string]$testHash) {
    if (-not (Test-Path -LiteralPath $installStatePath)) { return $false }
    try {
        $state = Get-Content -LiteralPath $installStatePath -Raw | ConvertFrom-Json
        return $state.deviceSerial -eq $DeviceSerial -and
            $state.debugApkSha256 -eq $debugHash -and
            $state.testApkSha256 -eq $testHash -and
            (Test-InstalledPackage "com.iaido.app") -and
            (Test-InstalledPackage "com.iaido.app.test")
    } catch {
        return $false
    }
}

function Save-InstallState([string]$debugHash, [string]$testHash) {
    $state = [ordered]@{
        deviceSerial = $DeviceSerial
        debugApkSha256 = $debugHash
        testApkSha256 = $testHash
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $installStatePath -Encoding utf8
}

$animationSettingNames = @(
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale"
)
$previousAnimationSettings = @{}
$deviceTuningApplied = $false
function Set-TestDeviceSettings {
    foreach ($settingName in $animationSettingNames) {
        $value = (& adb -s $DeviceSerial shell settings get global $settingName 2>$null | Out-String).Trim()
        if ($value -notmatch '^[0-9]+(?:\.[0-9]+)?$') {
            throw "Could not read Android animation setting '$settingName' (value '$value')"
        }
        $previousAnimationSettings[$settingName] = $value
        if ($value -ne "0") {
            & adb -s $DeviceSerial shell settings put global $settingName 0 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not disable Android animation setting '$settingName'" }
        }
    }
    $script:deviceTuningApplied = $true
    Write-Host "E2E device tuning: Android animations disabled for this run."
}

function Restore-TestDeviceSettings {
    if (-not $deviceTuningApplied) { return }
    foreach ($settingName in $animationSettingNames) {
        $value = $previousAnimationSettings[$settingName]
        if ($value) {
            & adb -s $DeviceSerial shell settings put global $settingName $value | Out-Null
        }
    }
    Write-Host "E2E device tuning: Android animation settings restored."
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
    Set-TestDeviceSettings
    if (-not $SkipBuild) {
        & $gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed with exit code $LASTEXITCODE" }
    } else {
        Write-Host "Skipping APK build; using existing artifacts."
    }
    if (-not (Test-Path -LiteralPath $debugApk) -or -not (Test-Path -LiteralPath $testApk)) {
        throw "Expected debug APKs were not produced"
    }

    $debugHash = Get-ApkHash $debugApk
    $testHash = Get-ApkHash $testApk
    $installRequired = $InstallOnly -or $SkipInstall -or -not (Test-InstallState $debugHash $testHash)
    if ($SkipInstall) {
        Write-Host "Skipping APK install; using the already-installed packages."
    } elseif ($installRequired) {
        & adb -s $DeviceSerial install -r $debugApk
        if ($LASTEXITCODE -ne 0) { throw "Installing the debug APK failed" }
        & adb -s $DeviceSerial install -r $testApk
        if ($LASTEXITCODE -ne 0) { throw "Installing the instrumentation APK failed" }
        Save-InstallState $debugHash $testHash
    } else {
        Write-Host "Reusing APKs already installed on $DeviceSerial."
    }

    if ($InstallOnly) {
        Write-Host "APK installation complete; no tests were started."
        $gradleExitCode = 0
    } elseif ($useDirectInstrumentation) {
        & adb -s $DeviceSerial shell am force-stop com.iaido.app.test
        & adb -s $DeviceSerial shell am force-stop com.iaido.app
        $instrumentationArguments = @("-r", "-w")
        if ($Class) {
            $instrumentationArguments += @("-e", "class", $Class)
        }
        $instrumentationArguments += "com.iaido.app.test/androidx.test.runner.AndroidJUnitRunner"
        $instrumentationOutput = @(& adb -s $DeviceSerial shell am instrument @instrumentationArguments 2>&1)
        $instrumentationOutput | Out-Host
        $instrumentationCodeLine = $instrumentationOutput | Where-Object { $_ -match 'INSTRUMENTATION_CODE:\s*(-?\d+)' } | Select-Object -Last 1
        if ($null -eq $instrumentationCodeLine -or $instrumentationCodeLine -notmatch 'INSTRUMENTATION_CODE:\s*(-?\d+)') {
            throw "Instrumentation did not report a completion code"
        }
        $instrumentationCode = [int]$Matches[1]
        # AndroidJUnitRunner uses -1 for a completed run. The stream above carries the
        # individual test result, so inspect both the completion code and the streamed
        # failure markers. A failed JUnit invocation can still end with code -1.
        $hasTestFailure = $instrumentationOutput -match 'INSTRUMENTATION_STATUS_CODE:\s+-2|FAILURES!!!|Process crashed while executing|Error in .+\('
        $gradleExitCode = if ($instrumentationCode -eq -1 -and -not $hasTestFailure) { 0 } else { 1 }
    } else {
        $testArguments = @()
        if ($Class) {
            $testArguments = @("-Pandroid.testInstrumentationRunnerArguments.class=$Class")
        }
        & $gradle :app:connectedDebugAndroidTest @testArguments --no-daemon --console=plain
        $gradleExitCode = $LASTEXITCODE
    }
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
    try { Restore-TestDeviceSettings } catch { Write-Warning "Could not restore Android animation settings: $_" }
    if ($previousAndroidSerial) { $env:ANDROID_SERIAL = $previousAndroidSerial } else { Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue }
    if ($startedProcess -and -not $KeepArtifacts) {
        Write-Host "Leaving emulator process $($startedProcess.Id) running; use emulator controls to stop it."
    }
}

exit $gradleExitCode

param(
    [ValidateSet('Prepare', 'Play', 'Rehearse')][string]$Mode = 'Play',
    [ValidateSet('Portrait', 'LandscapeRain')][string]$Scene = 'Portrait',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev-env.ps1"
Push-Location (Split-Path $PSScriptRoot -Parent)
$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = 'emulator-5554'
    if (-not ((& adb devices) -match '^emulator-5554\s+(device|offline)$')) {
        Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd', 'Chagok_Pleos_API_34', '-port', '5554', '-no-snapshot-load') -WindowStyle Hidden
    }
    $bootDeadline = (Get-Date).AddMinutes(5)
    while (((& adb shell getprop sys.boot_completed 2>$null) -join '').Trim() -ne '1') {
        if ((Get-Date) -gt $bootDeadline) { throw 'Presentation emulator did not boot within five minutes' }
        Start-Sleep -Seconds 2
    }
    if ($Build) {
        & .\gradlew.bat :app:assembleSmoke
        if ($LASTEXITCODE -ne 0) { throw 'Presentation APK build failed' }
        & adb install -r -g app/build/outputs/apk/smoke/app-smoke.apk
        if ($LASTEXITCODE -ne 0) { throw 'Presentation APK install failed' }
    }
    $demoArguments = @('tools/presentation-demo.py')
    if ($Scene -eq 'LandscapeRain') { $demoArguments += '--landscape-rain' }
    if ($Mode -eq 'Prepare') { $demoArguments += '--prepare' }
    if ($Mode -eq 'Rehearse') { $demoArguments += '--rehearse' }
    & python @demoArguments
    if ($LASTEXITCODE -ne 0) { throw 'Presentation failed; check the emulator and script output' }
} finally { $env:ANDROID_SERIAL = $previousSerial; Pop-Location }

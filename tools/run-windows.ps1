param([string]$Avd = 'Chagok_Pleos_API_34', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev-env.ps1"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
    }
    $serial = 'emulator-5554'
    $devices = & adb devices
    if (-not ($devices -match "^$serial\s+(device|offline)$")) {
        Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList @('-avd', $Avd, '-port', '5554', '-no-snapshot-load') -WindowStyle Hidden
    }
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 3
        $booted = (& adb -s $serial shell getprop sys.boot_completed 2>$null) -join ''
        if ((Get-Date) -gt $deadline) { throw 'Emulator did not boot within five minutes.' }
    } until ($booted.Trim() -eq '1')
    & adb -s $serial install -r -g app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
    & adb -s $serial shell am start -n io.chagok.app/.companion.CompanionActivity
    if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
} finally {
    Pop-Location
}

param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev-env.ps1"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:testSmokeUnitTest :app:assembleDebug :app:assembleSmoke :app:assembleSmokeAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Build or unit tests failed' }
    }
    & adb install -r -g app/build/outputs/apk/smoke/app-smoke.apk
    if ($LASTEXITCODE -ne 0) { throw 'Smoke app install failed' }
    & adb install -r -g app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation install failed' }
    & python tools/smoke-device.py
    if ($LASTEXITCODE -ne 0) { throw 'Device smoke tests failed' }
} finally { Pop-Location }

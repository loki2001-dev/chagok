param([switch]$SkipBuild, [switch]$TestOnly)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev-env.ps1"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleSmoke :app:assembleSmokeAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Probe build failed' }
    }
    & adb install -r -g app/build/outputs/apk/smoke/app-smoke.apk
    if ($LASTEXITCODE -ne 0) { throw 'Probe installation failed' }
    & adb install -r -g app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Instrumentation installation failed' }
    if ($TestOnly) { & python tools/companion-split-demo.py }
    else { & python tools/companion-split-demo.py --showcase }
    if ($LASTEXITCODE -ne 0) { throw 'Split-window verification failed' }
} finally { Pop-Location }

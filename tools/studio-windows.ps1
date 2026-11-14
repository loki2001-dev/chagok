$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev-env.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
Start-Process -FilePath "$projectRoot/.dev/android-studio/bin/studio64.exe" -ArgumentList "`"$projectRoot`""

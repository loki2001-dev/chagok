# Dot-source this file: . .\tools\dev-env.ps1
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $projectRoot '.dev/android-studio/jbr'
$env:ANDROID_HOME = Join-Path $projectRoot '.dev/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.dev/android-user'
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

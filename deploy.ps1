param(
    [switch]$NoLaunch = $false
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "C:\Users\shiel\AppData\Local\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
$env:ADB_MDNS_OPENSCREEN = "1"

Write-Host "Building Civic 5MT Debug APK..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleDebug

$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
    Write-Error "APK not found at $apk"
    exit 1
}

Write-Host "Locating paired device via auto-connect..." -ForegroundColor Cyan
& "$PSScriptRoot\connect-phone.ps1"

$devOutput = & adb devices | Out-String
$target = $null
if ($devOutput -match "(\S+)\s+device\b") {
    $target = $matches[1]
}

Write-Host "Installing update to phone..." -ForegroundColor Green
if ($target) {
    & adb -s $target install -r $apk
} else {
    & adb install -r $apk
}

if (!$NoLaunch) {
    Write-Host "Launching Civic 5MT..." -ForegroundColor Green
    if ($target) {
        & adb -s $target shell am start -n com.shieldrj.civic5mt.dev/com.shieldrj.civic5mt.ui.MainActivity | Out-Null
    } else {
        & adb shell am start -n com.shieldrj.civic5mt.dev/com.shieldrj.civic5mt.ui.MainActivity | Out-Null
    }
}

Write-Host "Done!" -ForegroundColor Green

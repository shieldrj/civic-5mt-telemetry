param(
    [switch]$NoLaunch = $false
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "C:\Users\shiel\AppData\Local\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"

Write-Host "🔨 Building Civic 5MT Debug APK..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleDebug

$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
    Write-Error "APK not found at $apk"
    exit 1
}

Write-Host "🔍 Locating paired device via mDNS auto-connect..." -ForegroundColor Cyan

$connected = $false
for ($i = 1; $i -le 5; $i++) {
    $devices = adb devices | Select-String "device$"
    if ($devices) {
        $connected = $true
        break
    }
    adb mdns services | Out-Null
    Start-Sleep -Seconds 1
}

if (!$connected) {
    $mdnsOutput = adb mdns services | Out-String
    if ($mdnsOutput -match "(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+)") {
        $endpoint = $matches[1]
        Write-Host "⚡ Auto-connecting to discovered endpoint: $endpoint..." -ForegroundColor Yellow
        adb connect $endpoint | Out-Null
    }
}

Write-Host "📲 Installing update to phone..." -ForegroundColor Green
adb install -r $apk

if (!$NoLaunch) {
    Write-Host "🚀 Launching Civic 5MT..." -ForegroundColor Green
    adb shell am start -n com.shieldrj.civic5mt.dev/com.shieldrj.civic5mt.ui.MainActivity | Out-Null
}

Write-Host "✅ Done!" -ForegroundColor Green

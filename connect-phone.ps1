# Connect-Phone.ps1: One-click wireless ADB connection for Robert's Phone

$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
$env:ANDROID_HOME = 'C:\Users\shiel\AppData\Local\Android\Sdk'
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
$env:ADB_MDNS_OPENSCREEN = '1'

Write-Host "Searching for your phone on Wi-Fi..." -ForegroundColor Cyan

# 1. Start ADB server with mDNS enabled
& adb start-server | Out-Null

# 2. Check if phone is already connected and online
$devs = & adb devices | Out-String
if ($devs -match '\bdevice\b') {
    Write-Host "Phone is already connected and ready!" -ForegroundColor Green
    & adb devices
    exit 0
}

# 3. Wait up to 6 seconds for mDNS discovery
$connected = $false
for ($i = 1; $i -le 6; $i++) {
    $mdns = & adb mdns services | Out-String
    if ($mdns -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+)') {
        $endpoint = $matches[1]
        Write-Host "Discovered phone at $endpoint! Connecting..." -ForegroundColor Yellow
        & adb connect $endpoint | Out-Null
        Start-Sleep -Seconds 1
        $devs = & adb devices | Out-String
        if ($devs -match '\bdevice\b') {
            $connected = $true
            break
        }
    }
    Start-Sleep -Seconds 1
}

# 4. Fallback: check recent known active ports on phone
if (-not $connected) {
    Write-Host "Checking recent ports on 192.168.1.225..." -ForegroundColor Yellow
    $candidatePorts = @(39311, 38835, 38061, 37093)
    foreach ($p in $candidatePorts) {
        $ep = "192.168.1.225:$p"
        & adb connect $ep | Out-Null
        Start-Sleep -Milliseconds 500
        $devs = & adb devices | Out-String
        if ($devs -match '\bdevice\b') {
            $connected = $true
            break
        }
    }
}

# 5. Final report
if ($connected) {
    Write-Host "Phone is connected and ready for deployment!" -ForegroundColor Green
    & adb devices
} else {
    Write-Host "Could not connect automatically." -ForegroundColor Red
    Write-Host "Please ensure Wireless Debugging is toggled ON in Developer Options on your phone." -ForegroundColor Yellow
    & adb devices
}

# Connect-Phone.ps1: One-click wireless ADB connection for Robert's Phone
$ErrorActionPreference = "SilentlyContinue"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "C:\Users\shiel\AppData\Local\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
$env:ADB_MDNS_OPENSCREEN = "1"

Write-Host "📱 Connecting to your phone on Wi-Fi..." -ForegroundColor Cyan

# 1. Check if phone is already connected and online
$devs = & adb devices | Out-String
if ($devs -match "(\S+)\s+device\b") {
    Write-Host "✅ Phone is already connected and ready!" -ForegroundColor Green
    & adb devices
    exit 0
}

# 2. Start ADB server with mDNS support
& adb start-server | Out-Null
Start-Sleep -Seconds 1

# 3. Check mDNS discovered services
$mdns = & adb mdns services | Out-String
if ($mdns -match "(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+)") {
    $endpoint = $matches[1]
    Write-Host "⚡ Found phone via mDNS: $endpoint. Connecting..." -ForegroundColor Yellow
    & adb connect $endpoint | Out-Null
    Start-Sleep -Seconds 1
}

$devs = & adb devices | Out-String
if ($devs -match "(\S+)\s+device\b") {
    Write-Host "✅ Connected successfully via mDNS!" -ForegroundColor Green
    & adb devices
    exit 0
}

# 4. Fallback: Fast auto-scan for phone's open wireless debugging port
$ip = "192.168.1.225"
Write-Host "🔍 Locating active wireless debugging port on $ip..." -ForegroundColor Yellow

$foundPort = $null
$ports = 32000..46000
$tasks = @()

foreach ($port in $ports) {
    $client = [System.Net.Sockets.TcpClient]::new()
    $iar = $client.BeginConnect($ip, $port, $null, $null)
    $tasks += [PSCustomObject]@{
        Port = $port
        Client = $client
        AR = $iar
    }
    if ($tasks.Count -ge 200) {
        Start-Sleep -Milliseconds 120
        foreach ($t in $tasks) {
            if ($t.AR.IsCompleted) {
                try {
                    $t.Client.EndConnect($t.AR)
                    Write-Host "FOUND OPEN PORT: $($t.Port)" -ForegroundColor Green
                    $foundPort = $t.Port
                } catch {}
            }
            $t.Client.Dispose()
        }
        $tasks = @()
        if ($foundPort) { break }
    }
}

if ($tasks.Count -gt 0 -and !$foundPort) {
    Start-Sleep -Milliseconds 120
    foreach ($t in $tasks) {
        if ($t.AR.IsCompleted) {
            try {
                $t.Client.EndConnect($t.AR)
                Write-Host "FOUND OPEN PORT: $($t.Port)" -ForegroundColor Green
                $foundPort = $t.Port
            } catch {}
        }
        $t.Client.Dispose()
    }
}

if ($foundPort) {
    Write-Host "⚡ Auto-connecting to phone at ${ip}:${foundPort}..." -ForegroundColor Yellow
    & adb connect "${ip}:${foundPort}" | Out-Null
    Start-Sleep -Seconds 1
}

# 5. Final check
$devs = & adb devices | Out-String
if ($devs -match "(\S+)\s+device\b") {
    Write-Host "✅ Phone is connected and ready!" -ForegroundColor Green
    & adb devices
} else {
    Write-Host "⚠️ Could not connect automatically." -ForegroundColor Red
    Write-Host "Please ensure Wireless Debugging is toggled ON in Developer Options on your phone." -ForegroundColor Yellow
    & adb devices
}


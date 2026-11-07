# Connect-Phone.ps1: Fast wireless ADB connection for Robert's Phone
param(
    [Parameter(Mandatory = $false, Position = 0)]
    [string]$Target
)

$ErrorActionPreference = "SilentlyContinue"

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:ANDROID_HOME = "C:\Users\shiel\AppData\Local\Android\Sdk"
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
$env:ADB_MDNS_OPENSCREEN = "1"

$defaultIp = "192.168.1.225"
$portCacheFile = Join-Path $PSScriptRoot ".last-adb-port"

Write-Host "[ADB] Connecting to your phone on Wi-Fi..." -ForegroundColor Cyan

# 0. If user passed a port or ip:port directly, connect immediately
if ($Target) {
    $endpoint = if ($Target -match ":") { $Target } else { "$($defaultIp):$($Target)" }
    Write-Host "[ADB] Connecting directly to $endpoint..." -ForegroundColor Yellow
    & adb connect $endpoint | Out-Null
    $devs = & adb devices | Out-String
    if ($devs -match "(\S+)\s+device\b") {
        if ($endpoint -match ":(\d+)$") { Set-Content -Path $portCacheFile -Value $matches[1] -NoNewline }
        Write-Host "[OK] Connected to $endpoint!" -ForegroundColor Green
        & adb devices
        exit 0
    }
}

# 1. Check if phone is already connected and online (also starts daemon if needed)
$devs = & adb devices | Out-String
if ($devs -match "(\S+)\s+device\b") {
    Write-Host "[OK] Phone is already connected and ready!" -ForegroundColor Green
    & adb devices
    exit 0
}

$ip = $defaultIp

# 2. Try cached port from last session (instant check)
if (Test-Path $portCacheFile) {
    $cachedPort = (Get-Content $portCacheFile -Raw).Trim()
    if ($cachedPort -match "^\d+$") {
        $tc = [System.Net.Sockets.TcpClient]::new()
        try {
            if ($tc.ConnectAsync($ip, [int]$cachedPort).Wait(350) -and $tc.Connected) {
                $tc.Dispose()
                $endpoint = "$($ip):$($cachedPort)"
                Write-Host "[ADB] Reconnecting to cached port $endpoint..." -ForegroundColor Yellow
                & adb connect $endpoint | Out-Null
                $devs = & adb devices | Out-String
                if ($devs -match "(\S+)\s+device\b") {
                    Write-Host "[OK] Connected via cached port!" -ForegroundColor Green
                    & adb devices
                    exit 0
                }
            }
        } catch {}
        $tc.Dispose()
    }
}

# 3. Check mDNS discovered services (fast 500ms check now that router hairpin_mode is enabled)
for ($attempt = 1; $attempt -le 2; $attempt++) {
    $mdns = & adb mdns services | Out-String
    if ($mdns -match "(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d+)") {
        $endpoint = $matches[0]
        $port = $matches[2]
        Write-Host "[ADB] Found phone via mDNS: $endpoint. Connecting..." -ForegroundColor Yellow
        & adb connect $endpoint | Out-Null
        $devs = & adb devices | Out-String
        if ($devs -match "(\S+)\s+device\b") {
            Set-Content -Path $portCacheFile -Value $port -NoNewline
            Write-Host "[OK] Connected successfully via mDNS!" -ForegroundColor Green
            & adb devices
            exit 0
        }
    }
    if ($attempt -eq 1) {
        $p = [System.Net.NetworkInformation.Ping]::new()
        try { $null = $p.Send($ip, 400) } catch {}
        $p.Dispose()
        Start-Sleep -Milliseconds 400
    }
}

# 4. Fast C# parallel port scanner fallback
Write-Host "[ADB] Fast-scanning wireless debugging port on $ip..." -ForegroundColor Yellow

if (-not ("PhonePortScanner" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

public static class PhonePortScanner {
    public static int FindOpenPort(string ip, int startPort, int endPort, int batchSize, int timeoutMs) {
        int foundPort = -1;
        for (int b = startPort; b <= endPort && foundPort == -1; b += batchSize) {
            int limit = Math.Min(b + batchSize - 1, endPort);
            int count = limit - b + 1;
            Task[] tasks = new Task[count];
            for (int i = 0; i < count; i++) {
                int port = b + i;
                tasks[i] = Task.Run(async () => {
                    if (Volatile.Read(ref foundPort) != -1) return;
                    using (var client = new TcpClient()) {
                        try {
                            var connectTask = client.ConnectAsync(ip, port);
                            if (await Task.WhenAny(connectTask, Task.Delay(timeoutMs)) == connectTask && client.Connected) {
                                Interlocked.CompareExchange(ref foundPort, port, -1);
                            }
                        } catch {}
                    }
                });
            }
            Task.WaitAll(tasks);
        }
        return foundPort;
    }
}
"@
}

$foundPort = [PhonePortScanner]::FindOpenPort($ip, 30000, 49999, 1000, 350)

if ($foundPort -gt 0) {
    Write-Host "FOUND OPEN PORT: $foundPort" -ForegroundColor Green
    Set-Content -Path $portCacheFile -Value $foundPort -NoNewline
    $endpoint = "$($ip):$($foundPort)"
    Write-Host "[ADB] Auto-connecting to phone at $endpoint..." -ForegroundColor Yellow
    & adb connect $endpoint | Out-Null
}

# 5. Final check
$devs = & adb devices | Out-String
if ($devs -match "(\S+)\s+device\b") {
    Write-Host "[OK] Phone is connected and ready!" -ForegroundColor Green
    & adb devices
} else {
    Write-Host "[WARN] Could not connect automatically." -ForegroundColor Red
    Write-Host "Please ensure Wireless Debugging is toggled ON in Developer Options on your phone." -ForegroundColor Yellow
    & adb devices
}

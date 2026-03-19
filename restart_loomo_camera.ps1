$ErrorActionPreference = "Stop"

$adb = "C:\Users\rs280\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$device = "54AGE18GL20227"
$package = "com.example.loomoagent"

Write-Host "Restarting Segway host/vision services on $device..."
& $adb -s $device shell am force-stop com.segway.robot.host
& $adb -s $device shell am force-stop com.segway.robot.host.coreservice.vision
Start-Sleep -Seconds 4

Write-Host "Launching Segway host..."
& $adb -s $device shell monkey -p com.segway.robot.host -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 4

Write-Host "Launching LoomoAgent..."
& $adb -s $device shell monkey -p $package -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 6

Write-Host "Refreshing local port forward..."
& $adb -s $device forward tcp:18080 tcp:8080 | Out-Null

Write-Host "Checking /snapshot..."
$out = Join-Path $PSScriptRoot "tmp-snapshot-restart.jpg"
Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:18080/snapshot?t=$([DateTime]::UtcNow.Ticks)" -OutFile $out -TimeoutSec 10 | Out-Null
$item = Get-Item $out
Write-Host "Snapshot OK:" $item.FullName "(" $item.Length "bytes )"

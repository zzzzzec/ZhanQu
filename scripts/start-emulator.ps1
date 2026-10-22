[CmdletBinding()]
param(
    [ValidateSet(30,36)]
    [int]$AndroidApi = 36
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $projectRoot '.gradle/local-tools/env.ps1')
$toolRoot = Join-Path $projectRoot '.gradle/local-tools'
$emulator = Join-Path $env:ANDROID_HOME 'emulator/emulator.exe'
if (-not (Test-Path $emulator)) { throw 'Install the Android Emulator in the local SDK first.' }
$env:ANDROID_AVD_HOME = Join-Path $toolRoot 'avds'
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME -Force | Out-Null
$name = "ZhanQu_API$AndroidApi"
$serial = 'emulator-5580'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$devices = (& $adb devices) -join "`n"
if ($devices -match "(?m)^$serial\s+") {
    $runningName = (& $adb -s $serial emu avd name) -join "`n"
    if ($runningName -notmatch "(?m)^$name\s*$") { throw 'Emulator port 5580 belongs to a different instance.' }
    Write-Output "Already running: $name ($serial)"
    return
}
if (-not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$name.ini"))) { throw "Create the $name AVD before launching." }
$acceleration = & $emulator -accel-check 2>&1
if ($LASTEXITCODE -ne 0) { throw ($acceleration -join "`n") }
$arguments = @('-avd',$name,'-port','5580','-scale','0.38','-no-boot-anim','-no-metrics','-crash-report-mode','never')
if ($AndroidApi -eq 30) { $arguments += @('-gpu','swiftshader','-feature','-Vulkan') }
else { $arguments += @('-gpu','auto') }
$process = Start-Process -FilePath $emulator -ArgumentList $arguments `
    -WorkingDirectory $projectRoot -WindowStyle Normal -PassThru `
    -RedirectStandardOutput (Join-Path $toolRoot 'emulator-stdout.log') `
    -RedirectStandardError (Join-Path $toolRoot 'emulator-stderr.log')
Write-Output "Started visible emulator: $name ($serial), process $($process.Id)"
Write-Output 'Use -Serial emulator-5580 for all test and UI commands. Phone data is separate.'

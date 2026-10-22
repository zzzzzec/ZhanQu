[CmdletBinding()]
param(
    [ValidateSet('devices', 'install', 'test', 'launch')]
    [string]$Action = 'test',
    [string]$Serial,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localEnvironment = Join-Path $projectRoot '.gradle\local-tools\env.ps1'
if (Test-Path $localEnvironment) { . $localEnvironment }
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { 'adb' }
$appId = 'dev.zhanqu.app.devtest'
$testId = "$appId.test"

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = & $adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}

Push-Location $projectRoot
try {
    $deviceList = Invoke-Adb -Arguments @('devices', '-l')
    if ($Action -eq 'devices') { $deviceList; return }
    $devices = @($deviceList | ForEach-Object {
        if ("$_" -match '^(\S+)\s+(device|unauthorized|offline)\b') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })
    if ($Serial) {
        $selected = @($devices | Where-Object Serial -eq $Serial)
    } else {
        $selected = $devices
    }
    if ($selected.Count -ne 1) { throw 'Connect one phone, or select its serial with -Serial. Run -Action devices first.' }
    if ($selected[0].State -ne 'device') { throw "Phone state is $($selected[0].State). Unlock it and allow USB debugging." }
    $Serial = $selected[0].Serial
    $apiLevel = [int]((Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
    if ($apiLevel -lt 30) { throw 'This app requires Android 11 / API 30 or newer.' }
    $abis = (Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'getprop', 'ro.product.cpu.abilist')) -join ''
    $primaryAbi = ($abis.Trim() -split ',')[0]
    $targetAbi = if ($primaryAbi -eq 'x86_64') { 'x86_64' } elseif ($abis -match '(^|,)arm64-v8a(,|\s|$)') { 'arm64-v8a' } else { throw 'Tests require an arm64-v8a device or x86_64 emulator.' }

    if ($Action -in @('install', 'test')) {
        if (-not $SkipBuild) {
            $tasks = @('assembleDebug', '-PzhanquDeviceTest=true', "-PzhanquAbis=$targetAbi")
            if ($Action -eq 'test') { $tasks += 'assembleDebugAndroidTest' }
            $localBuild = Join-Path $projectRoot '.gradle\local-tools\build.ps1'
            if (Test-Path $localBuild) { & $localBuild @tasks }
            else { & .\gradlew.bat @tasks --console=plain }
            if ($LASTEXITCODE -ne 0) { throw 'Build failed; nothing was installed.' }
        }
        $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        $metadataPath = Join-Path (Split-Path $apk -Parent) 'output-metadata.json'
        $metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
        if ($metadata.applicationId -ne $appId) { throw 'APK is not the isolated device-test app. Rebuild without -SkipBuild.' }
        Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', '-t', $apk)
        if ($Action -eq 'test') {
            $testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
            $testMetadata = Get-Content (Join-Path (Split-Path $testApk -Parent) 'output-metadata.json') -Raw | ConvertFrom-Json
            if ($testMetadata.applicationId -ne $testId) { throw 'Test APK targets a different application.' }
            Invoke-Adb -Arguments @('-s', $Serial, 'install', '-r', '-t', $testApk)
            $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
            $reportDir = Join-Path $projectRoot "reports\device-test\$runId"
            New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
            $instrumentation = & $adb -s $Serial shell am instrument -w -r -e runId $runId -e class dev.zhanqu.app.DeviceSmokeTest "$testId/androidx.test.runner.AndroidJUnitRunner" 2>&1 |
                Tee-Object -FilePath (Join-Path $reportDir 'instrumentation.txt')
            $instrumentationExit = $LASTEXITCODE
            $resultText = $instrumentation -join "`n"
            $passed = $instrumentationExit -eq 0 -and $resultText -match '(?m)^OK \(4 tests\)' -and $resultText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed'
            $screenshotsCopied = $false
            try {
                . (Join-Path $PSScriptRoot 'Export-TestArtifacts.ps1')
                Export-TestArtifacts -Adb $adb -Serial $Serial -Package $appId -RemoteDirectory "files/device-tests/$runId" -Destination (Join-Path $reportDir 'screenshots')
                $screenshotsCopied = $true
            } catch { $_ | Out-String | Set-Content (Join-Path $reportDir 'screenshots-transfer.txt') }
            [ordered]@{ appId=$appId; passed=$passed; screenshotsCopied=$screenshotsCopied; androidApi=$apiLevel; runId=$runId } |
                ConvertTo-Json | Set-Content (Join-Path $reportDir 'result.json') -Encoding utf8
            $instrumentation
            Write-Host "Device report: $reportDir"
            if (-not $passed) { throw 'Device tests did not pass. Inspect instrumentation.txt and screenshots.' }
            if (-not $screenshotsCopied) { throw 'Tests passed, but screenshot transfer failed. Inspect screenshots-transfer.txt.' }
        }
    }
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', 'am', 'start', '-W', '-n', "$appId/dev.zhanqu.app.MainActivity")
} finally {
    Pop-Location
}

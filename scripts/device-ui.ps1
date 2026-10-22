[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateSet('dump','tapText','tapContains','tapDesc','tapId','input','inputId','tap','swipe','back','enter')]
    [string]$Mode = 'dump',
    [string]$Target,
    [string]$Value,
    [string]$Text,
    [int]$X, [int]$Y, [int]$X1, [int]$Y1, [int]$X2, [int]$Y2
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $projectRoot '.gradle\local-tools\env.ps1')
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not $Serial) {
    $connected = @(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($connected.Count -ne 1) { throw 'Select one authorized phone with -Serial.' }
    $Serial = $connected[0]
}
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$directory = Join-Path $projectRoot "reports\phone-ui\$runId"
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$arguments = @('-s',$Serial,'shell','am','instrument','-w','-r','-e','runId',$runId,'-e','mode',$Mode)
foreach ($entry in @{ target=$Target; value=$Value; text=$Text }.GetEnumerator()) {
    if ($entry.Value) {
        # adb shell combines arguments again; quote text for the Android shell, including spaces/Chinese.
        $quoted = "'" + $entry.Value.Replace("'", "'\''") + "'"
        $arguments += @('-e',$entry.Key,$quoted)
    }
}
foreach ($key in @('X','Y','X1','Y1','X2','Y2')) {
    if ($PSBoundParameters.ContainsKey($key)) { $arguments += @('-e',$key.ToLower(),[string]$PSBoundParameters[$key]) }
}
$arguments += 'dev.zhanqu.uidriver.test/androidx.test.runner.AndroidJUnitRunner'
$output = & $adb @arguments 2>&1
$code = $LASTEXITCODE
$output | Set-Content (Join-Path $directory 'instrumentation.txt') -Encoding utf8
if ($code -ne 0 -or ($output -join "`n") -notmatch '(?m)^OK \(1 test\)' -or ($output -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed') { $output; throw "Phone UI command failed: $directory" }
. (Join-Path $PSScriptRoot 'Export-TestArtifacts.ps1')
Export-TestArtifacts -Adb $adb -Serial $Serial -Package dev.zhanqu.uidriver -RemoteDirectory "files/commands/$runId" -Destination $directory
[pscustomobject]@{ directory=$directory; screenshot=(Join-Path $directory 'screen.png'); tree=(Join-Path $directory 'tree.xml') } | ConvertTo-Json

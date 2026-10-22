[CmdletBinding()]
param([string]$Serial)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $projectRoot '.gradle\local-tools\env.ps1')
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not $Serial) {
    $devices=@(& $adb devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
    if ($devices.Count -ne 1) { throw 'Choose one authorized device with -Serial.' }
    $Serial=$devices[0]
}
# Read only the isolated debug app. Original and preview databases are never accessed.
$directory=Join-Path $projectRoot ('reports\device-data\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive=Join-Path $directory 'databases.tar'
$process=[System.Diagnostics.Process]::new()
$process.StartInfo.FileName=$adb
$process.StartInfo.UseShellExecute=$false
$process.StartInfo.CreateNoWindow=$true
$process.StartInfo.RedirectStandardOutput=$true
$process.StartInfo.RedirectStandardError=$true
foreach ($argument in @('-s',$Serial,'exec-out','run-as','dev.zhanqu.app.devtest','tar','-cf','-','databases')) { $process.StartInfo.ArgumentList.Add($argument) }
[void]$process.Start()
$stream=[System.IO.File]::Create($archive)
try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
$errorText=$process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw "Debug database export failed: $errorText" }
& tar.exe -xf $archive -C $directory
if ($LASTEXITCODE -ne 0) { throw 'Could not extract database copy.' }
& python (Join-Path $projectRoot 'tools\analyze_device_records.py') $directory
if ($LASTEXITCODE -ne 0) { throw 'Could not analyze copied database.' }

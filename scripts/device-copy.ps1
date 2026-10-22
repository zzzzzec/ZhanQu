[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Serial,
      [Parameter(Mandatory=$true)][string]$Source,
      [Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference='Stop'
if ($Source -notmatch '^cache/page-snapshots/page-[A-Za-z0-9-]+\.zip$') { throw 'Only generated debug page snapshots may be read.' }
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $projectRoot '.gradle\local-tools\env.ps1')
$process=[System.Diagnostics.Process]::new()
$process.StartInfo.FileName=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$process.StartInfo.UseShellExecute=$false
$process.StartInfo.CreateNoWindow=$true
$process.StartInfo.RedirectStandardOutput=$true
$process.StartInfo.RedirectStandardError=$true
foreach ($argument in @('-s',$Serial,'exec-out','run-as','dev.zhanqu.app.devtest','cat',$Source)) { $process.StartInfo.ArgumentList.Add($argument) }
[void]$process.Start()
$stream=[System.IO.File]::Create([System.IO.Path]::GetFullPath($Destination))
try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
$errorText=$process.StandardError.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw $errorText }

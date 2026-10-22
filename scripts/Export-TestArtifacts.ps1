function Export-TestArtifacts {
    param(
        [string]$Adb, [string]$Serial,
        [ValidateSet('dev.zhanqu.uidriver','dev.zhanqu.app.devtest')][string]$Package,
        [string]$RemoteDirectory, [string]$Destination
    )
    if ($RemoteDirectory -notmatch '^files/(commands|device-tests)/[A-Za-z0-9-]+$') { throw 'Unexpected test artifact path.' }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $archive = Join-Path $Destination 'artifacts.tar'
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = $Adb
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in @('-s',$Serial,'exec-out','run-as',$Package,'tar','-C',$RemoteDirectory,'-cf','-','.')) { $process.StartInfo.ArgumentList.Add($argument) }
    [void]$process.Start()
    $stream = [System.IO.File]::Create($archive)
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Test artifact export failed: $errorText" }
    & tar.exe -xf $archive -C $Destination
    if ($LASTEXITCODE -ne 0) { throw 'Test artifact extraction failed.' }
}

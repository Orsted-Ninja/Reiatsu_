$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop 'StorageSense.lnk'
$wsh = New-Object -ComObject WScript.Shell
$sc = $wsh.CreateShortcut($lnkPath)
$sc.TargetPath = 'F:\ASCENT\idea\Start_StorageSense.bat'
$sc.WorkingDirectory = 'F:\ASCENT\idea\pc_port'
$sc.Description = 'StorageSense - 100% Local AI Storage Assistant'
$sc.Save()
Write-Host "Success! Desktop shortcut created at: $lnkPath"

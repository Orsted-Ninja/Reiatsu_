$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
if (-not $scriptDir) { $scriptDir = $PSScriptRoot }
$targetBat = Join-Path $scriptDir "Start_StorageSense.bat"
$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop 'StorageSense.lnk'
$wsh = New-Object -ComObject WScript.Shell
$sc = $wsh.CreateShortcut($lnkPath)
$sc.TargetPath = $targetBat
$sc.WorkingDirectory = $scriptDir
$sc.Description = 'StorageSense - 100% Local AI Storage Assistant (PC Port)'
$sc.Save()
Write-Host "Success! Desktop shortcut created at: $lnkPath"

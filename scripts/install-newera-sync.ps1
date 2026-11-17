param(
    [Parameter(Mandatory)][string]$ServerUrl,
    [Parameter(Mandatory)][string]$TokenFile,
    [Parameter(Mandatory)][string]$SettingsPath
)

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'sync-newera.ps1'
$tokenPath = (Resolve-Path -LiteralPath $TokenFile).Path
$desktopPath = (Resolve-Path -LiteralPath $SettingsPath).Path
# Task Scheduler parses these arguments itself; reject embedded quotes and line breaks.
foreach ($value in @($script, $ServerUrl, $tokenPath, $desktopPath)) {
    if ($value -match '["\r\n]') { throw 'Paths and URL must not contain quotes or line breaks.' }
}
# Verify the actual export before registering an automatic task.
& $script -ServerUrl $ServerUrl -TokenFile $tokenPath -SettingsPath $desktopPath
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Initial sync failed.' }
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -File "{0}" -ServerUrl "{1}" -TokenFile "{2}" -SettingsPath "{3}"' -f `
    $script, $ServerUrl, $tokenPath, $desktopPath
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'Hex Mobile NewEra Sync' -Action $action -Trigger $trigger `
    -Settings $settings -Description 'Copy Hex text customizations from NewEra to the private mobile server every minute.' -Force | Out-Null
Write-Output 'NewEra sync installed and initial export verified.'

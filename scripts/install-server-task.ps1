# Keep inference available while this Windows user is logged in.
# No administrator service, router rule, public listener, or Funnel is created.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot 'server\.venv\Scripts\python.exe'
$server = Join-Path $projectRoot 'server'
if (-not (Test-Path -LiteralPath $python)) { throw 'Run uv sync --python 3.12 in server first.' }
$action = New-ScheduledTaskAction -Execute $python -Argument 'hex_server.py --backend vulkan' -WorkingDirectory $server
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'Hex Mobile Inference' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Private Hex Mobile transcription over Tailscale Serve.' -Force | Out-Null
Start-ScheduledTask -TaskName 'Hex Mobile Inference'

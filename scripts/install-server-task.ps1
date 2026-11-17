#Requires -RunAsAdministrator
# Keep inference available after reboot, including before desktop sign-in.
# No administrator service, router rule, public listener, or Funnel is created.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot 'server\.venv\Scripts\python.exe'
$server = Join-Path $projectRoot 'server'
if (-not (Test-Path -LiteralPath $python)) { throw 'Run uv sync --python 3.12 in server first.' }
$action = New-ScheduledTaskAction -Execute $python -Argument 'hex_server.py --backend vulkan' -WorkingDirectory $server
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$startup = New-ScheduledTaskTrigger -AtStartup
$startup.Delay = 'PT30S'
$logon = New-ScheduledTaskTrigger -AtLogOn -User $user
# S4U needs no stored password or interactive desktop. Inference uses local
# files; Tailscale Serve handles the private network connection separately.
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'Hex Mobile Inference' -Action $action -Trigger @($startup, $logon) -Principal $principal -Settings $settings -Description 'Private Hex Mobile transcription over Tailscale Serve; starts at boot without desktop sign-in.' -Force | Out-Null
Start-ScheduledTask -TaskName 'Hex Mobile Inference'

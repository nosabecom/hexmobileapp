# Run from PowerShell on Godseye (or another Windows host with Vulkan drivers).
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $projectRoot 'server')
& uv sync --python 3.12 --frozen
if ($LASTEXITCODE -ne 0) { throw 'Server dependency installation failed' }
& .\.venv\Scripts\python.exe hex_server.py --backend vulkan
exit $LASTEXITCODE

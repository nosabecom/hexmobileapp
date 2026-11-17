[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ServerUrl,
    [Parameter(Mandatory)][string]$TokenFile,
    [string]$SettingsPath,
    [string]$ConfigPath,
    [string]$StatusFile
)

$ErrorActionPreference = 'Stop'
if (-not $StatusFile) { $StatusFile = Join-Path $PSScriptRoot 'newera-sync-status.json' }
try {
    $uri = [Uri]$ServerUrl
    if ($uri.Scheme -ne 'https' -or -not $uri.Host.EndsWith('.ts.net') -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'Use the private Tailscale HTTPS server URL.'
    }
    if (-not $SettingsPath) {
        $candidates = @(
            (Join-Path $env:APPDATA 'hex\settings.json'),
            (Join-Path $env:LOCALAPPDATA 'hex\settings.json'),
            (Join-Path $env:USERPROFILE '.config\hex\settings.json')
        )
        $existing = @($candidates | Where-Object { Test-Path -LiteralPath $_ })
        if ($existing.Count -ne 1) { throw 'Specify -SettingsPath for the Hex settings.json used on NewEra.' }
        $SettingsPath = $existing[0]
    }
    if ((Get-Item -LiteralPath $SettingsPath).Length -gt 4MB) { throw 'Desktop settings file is too large.' }
    Write-Verbose 'Reading desktop text settings'
    $settings = Get-Content -LiteralPath $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $settings.dictation_processing -and -not $settings.text_replacements) {
        throw 'This file does not contain Hex dictation customizations.'
    }
    # Export text settings and the user's transformation bundle, excluding provider settings and prompts.
    $processing = $settings.dictation_processing
    $allModes = @($processing.default_mode) + @($processing.modes)
    $safeModes = @()
    foreach ($mode in $allModes) {
        if ($null -eq $mode) { continue }
        $safeRules = @()
        foreach ($rule in $mode.replacements) {
            $safeRules += @{ matched_phrase = [string]$rule.matched_phrase; output = [string]$rule.output }
        }
        $safeModes += @{
            name = [string]$mode.name
            replacements = @($safeRules)
            transformations = @($mode.transformations | Where-Object { $null -ne $_ })
            post_processing = @{ enabled = [bool]$mode.post_processing.enabled }
        }
    }
    $legacy = @()
    foreach ($rule in $settings.text_replacements) {
        $legacy += @{ matched_phrase = [string]$rule.matched_phrase; output = [string]$rule.output }
    }
    $payload = @{ text_replacements = @($legacy) }
    # Shared vocabulary is separate from mode corrections in current desktop Hex.
    # Export only its text fields; the server supplies aliases to older mobile builds.
    $payload.vocabulary = @($settings.vocabulary | Where-Object { $null -ne $_ } | ForEach-Object {
        if ($_ -is [string]) { $_ }
        else { @{ term = $_.term; sounds_like = @($_.sounds_like | Where-Object { $null -ne $_ }) } }
    })
    if (-not $ConfigPath) {
        $configRoot = Join-Path $env:USERPROFILE '.config\hex'
        $configs = @(@('hex.config.ts', 'hex.config.js') | ForEach-Object { Join-Path $configRoot $_ } | Where-Object { Test-Path -LiteralPath $_ })
        if ($configs.Count -gt 1) { throw 'Keep only one Hex config file.' }
        if ($configs.Count -eq 1) { $ConfigPath = $configs[0] }
    }
    if ($ConfigPath) {
        Write-Verbose 'Bundling desktop transformations'
        $bundleTool = Join-Path (Split-Path $PSScriptRoot) 'sync\bundle.mjs'
        $bundleFile = Join-Path $PSScriptRoot 'newera-transformations.js'
        & node $bundleTool $ConfigPath $bundleFile 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile Hex transformations.' }
        # Get-Content adds filesystem metadata to strings in Windows PowerShell 5.1;
        # serializing that metadata can traverse an enormous provider object graph.
        $payload.script = [IO.File]::ReadAllText($bundleFile, [Text.Encoding]::UTF8)
        # Read Hex's own catalog. Compiling does not execute custom code on NewEra.
        $catalogPath = Join-Path (Split-Path $SettingsPath) 'personal-commands.json'
        $catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($catalog.lastReloadError) { throw 'Fix the desktop config reload error before syncing.' }
        $payload.script_ids = @($catalog.transformations | ForEach-Object { [string]$_.id })
    }
    if ($safeModes.Count -gt 0) {
        $otherModes = @()
        if ($safeModes.Count -gt 1) { $otherModes = @($safeModes[1..($safeModes.Count - 1)]) }
        $payload.dictation_processing = @{ default_mode = $safeModes[0]; modes = @($otherModes) }
    }
    $token = (Get-Content -LiteralPath $TokenFile -Raw).Trim()
    $body = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 30 -Compress))
    Write-Verbose 'Publishing snapshot to private server'
    $response = Invoke-RestMethod -Method Put -Uri ($ServerUrl.TrimEnd('/') + '/customizations') `
        -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $body -TimeoutSec 20 -UseBasicParsing
    @{ ok = $true; checkedAt = [DateTimeOffset]::UtcNow.ToString('o'); revision = $response.revision } |
        ConvertTo-Json | Set-Content -LiteralPath $StatusFile -Encoding UTF8
    Write-Verbose 'Snapshot published'
} catch {
    # Do not log exception objects: an HTTP failure may contain request credentials or body text.
    @{ ok = $false; checkedAt = [DateTimeOffset]::UtcNow.ToString('o');
        error = 'Sync failed. Check the settings path, token file, server version and Tailscale connection.' } |
        ConvertTo-Json | Set-Content -LiteralPath $StatusFile -Encoding UTF8
    Write-Error 'NewEra customization sync failed. The last successful snapshot is preserved.'
    exit 1
}

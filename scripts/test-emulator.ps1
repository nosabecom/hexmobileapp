param([string]$Serial = 'emulator-5554', [string]$Endpoint = 'http://127.0.0.1:8765')
$ErrorActionPreference = 'Stop'
if (-not $Serial.StartsWith('emulator-')) { throw 'This script is restricted to emulators.' }
Set-Location (Split-Path -Parent $PSScriptRoot)
& .\gradlew.bat :app:assembleDebug :fixture:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android build/checks failed' }
& adb -s $Serial install -r app\build\outputs\apk\debug\app-debug.apk
& adb -s $Serial install -r fixture\build\outputs\apk\debug\fixture-debug.apk
& adb -s $Serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& adb -s $Serial shell pm grant dev.nosa.hex android.permission.RECORD_AUDIO
& adb -s $Serial shell pm grant dev.nosa.hex android.permission.POST_NOTIFICATIONS
# Set the server URL and token in the app before running real-model tests.
if ($Endpoint -eq 'http://127.0.0.1:8765') { & adb -s $Serial reverse tcp:8765 tcp:8765 }
$result = & adb -s $Serial shell am instrument -w -e endpoint $Endpoint dev.nosa.hex.test/androidx.test.runner.AndroidJUnitRunner
$result | Write-Output
# Instrumentation terminates the app process; explicitly rebind Hex afterwards.
# Preserve any other accessibility services configured on the emulator.
$enabled = (& adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$others = @($enabled.Split(':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -notlike 'dev.nosa.hex/*' })
if ($others.Count) {
    & adb -s $Serial shell settings put secure enabled_accessibility_services ($others -join ':')
} else {
    & adb -s $Serial shell settings delete secure enabled_accessibility_services
}
Start-Sleep -Milliseconds 500
$restored = @($others) + 'dev.nosa.hex/dev.nosa.hex.BubbleService'
& adb -s $Serial shell settings put secure enabled_accessibility_services ($restored -join ':')
& adb -s $Serial shell settings put secure accessibility_enabled 1
if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Instrumentation tests failed; inspect output above.' }

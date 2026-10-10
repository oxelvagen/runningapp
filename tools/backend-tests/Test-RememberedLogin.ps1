param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$temporary=Join-Path $root "tmp/remembered-login-$([Guid]::NewGuid().ToString('N')).json"
function Phase([string]$name) {
    & $adb -s $Device shell am force-stop se.oxelv.mintracker
    $output=& $adb -s $Device shell am instrument -w -r -e class se.oxelv.mintracker.data.RememberedLoginDeviceTest -e session_phase $name se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { $output|Write-Output; throw "Cold-start phase $name failed" }
    Write-Output "PASS cold-start phase: $name"
}
try {
    & $adb -s $Device install -r (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk')
    & $adb -s $Device install -r (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    $accounts=Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
    $payload=@(@($accounts)[0..1]|ForEach-Object { @{email=$_.Email;password=$_.Credential.GetNetworkCredential().Password} })
    [IO.File]::WriteAllText($temporary,(ConvertTo-Json $payload -Compress),[Text.UTF8Encoding]::new($false))
    $null=& $adb -s $Device push $temporary /data/local/tmp/mintracker-test-accounts.json
    & $adb -s $Device shell run-as se.oxelv.mintracker mkdir -p files
    & $adb -s $Device shell run-as se.oxelv.mintracker cp /data/local/tmp/mintracker-test-accounts.json files/live-social-test-accounts.json
    & $adb -s $Device shell rm /data/local/tmp/mintracker-test-accounts.json
    Remove-Item -LiteralPath $temporary
    Phase login
    Phase restore
    Phase expire
    Phase restore
    Phase expire
    & $adb -s $Device shell svc wifi disable
    & $adb -s $Device shell svc data disable
    Phase restore_offline
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    Phase restore
    Phase logout
    Phase logged_out
} finally {
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    $null=& $adb -s $Device shell rm -f /data/local/tmp/mintracker-test-accounts.json
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker rm -f files/live-social-test-accounts.json
    $accounts=$null;$payload=$null
}

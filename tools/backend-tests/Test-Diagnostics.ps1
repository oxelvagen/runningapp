param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$temporary=Join-Path $root "tmp/diagnostic-login-$([Guid]::NewGuid().ToString('N')).json"
function SessionPhase([string]$name) {
    & $adb -s $Device shell am force-stop se.oxelv.mintracker
    $output=& $adb -s $Device shell am instrument -w -r -e class se.oxelv.mintracker.data.RememberedLoginDeviceTest -e session_phase $name se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { $output|Write-Output; throw "Session phase $name failed" }
    Write-Output "PASS diagnostic session $name"
}
function DiagnosticPhase([string]$name) {
    & $adb -s $Device shell am force-stop se.oxelv.mintracker
    $output=& $adb -s $Device shell am instrument -w -r -e class se.oxelv.mintracker.data.LiveDiagnosticsDeviceTest -e diagnostic_phase $name se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    if ($name -eq 'crash') {
        $exists=& $adb -s $Device shell run-as se.oxelv.mintracker test -f files/diagnostic-crash.json
        if ($LASTEXITCODE -ne 0) { throw 'Controlled crash did not write a spool' }
        Write-Output 'PASS controlled process crash wrote private spool'
    } elseif (($output -join "`n") -notmatch 'OK \(1 test\)') { $output|Write-Output; throw "Diagnostic phase $name failed" }
    else { Write-Output "PASS diagnostic phase $name" }
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
    SessionPhase login
    & $adb -s $Device shell svc wifi disable
    & $adb -s $Device shell svc data disable
    DiagnosticPhase queue
    SessionPhase restore_offline
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    Start-Sleep -Seconds 3
    DiagnosticPhase upload
    DiagnosticPhase crash
    DiagnosticPhase recover
    $ids=& $adb -s $Device shell run-as se.oxelv.mintracker cat files/diagnostic-test-ids.json
    [IO.File]::WriteAllText((Join-Path $root 'tmp/diagnostic-test-ids.json'),($ids -join "`n"),[Text.UTF8Encoding]::new($false))
    SessionPhase logout
    Write-Output 'PASS diagnostic integration sequence'
} finally {
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    & $adb -s $Device shell rm /data/local/tmp/mintracker-test-accounts.json
    & $adb -s $Device shell run-as se.oxelv.mintracker rm -f files/live-social-test-accounts.json
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}

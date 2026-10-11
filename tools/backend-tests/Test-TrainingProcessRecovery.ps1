param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$temporary=Join-Path $root "tmp/training-login-$([Guid]::NewGuid().ToString('N')).json"
function Invoke-TestPhase([string]$Class,[string]$Key,[string]$Phase) {
    $output=& $adb -s $Device shell am instrument -w -r -e class $Class -e $Key $Phase se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { $output|Write-Output; throw "Device phase $Phase failed" }
    Write-Output "PASS $Phase"
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
    & $adb -s $Device shell am force-stop se.oxelv.mintracker
    Invoke-TestPhase 'se.oxelv.mintracker.data.RememberedLoginDeviceTest' 'session_phase' 'login'
    # Exercise package replacement without clearing the encrypted session.
    & $adb -s $Device install -r (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk')
    Invoke-TestPhase 'se.oxelv.mintracker.data.RememberedLoginDeviceTest' 'session_phase' 'restore'
    & $adb -s $Device shell svc wifi disable
    & $adb -s $Device shell svc data disable
    & $adb -s $Device shell run-as se.oxelv.mintracker test -f files/training-process-id.txt
    if ($LASTEXITCODE -eq 0) {
        # Finish only this runner's previously interrupted fixture; never remove arbitrary training.
        Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'restore_save'
    } else {
        Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'repair_fixture'
    }
    Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'prepare'
    & $adb -s $Device shell am start -n se.oxelv.mintracker/.MainActivity | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $Device shell screencap -p /sdcard/mintracker-training-recovery.png
    $null=& $adb -s $Device pull /sdcard/mintracker-training-recovery.png (Join-Path $root 'tmp/stability-active-recovery.png')
    & $adb -s $Device shell rm /sdcard/mintracker-training-recovery.png
    $appPid=((& $adb -s $Device shell pidof se.oxelv.mintracker) -join '').Trim()
    if ($appPid -notmatch '^\d+$') { throw 'Expected a running app process before kill' }
    & $adb -s $Device shell run-as se.oxelv.mintracker kill -9 $appPid
    Write-Output 'PASS actual process SIGKILL issued (not force-stop)'
    Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'restore_save'
    Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'prepare'
    & $adb -s $Device shell am force-stop se.oxelv.mintracker
    Write-Output 'PASS explicit force-stop issued; recovery requires reopening'
    Invoke-TestPhase 'se.oxelv.mintracker.data.TrainingProcessDeviceTest' 'training_phase' 'restore_save'
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    Invoke-TestPhase 'se.oxelv.mintracker.data.RememberedLoginDeviceTest' 'session_phase' 'restore'
    Invoke-TestPhase 'se.oxelv.mintracker.data.RememberedLoginDeviceTest' 'session_phase' 'logout'
} finally {
    & $adb -s $Device shell svc wifi enable
    & $adb -s $Device shell svc data enable
    & $adb -s $Device shell input keyevent 224
    & $adb -s $Device shell wm dismiss-keyguard
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    $null=& $adb -s $Device shell rm -f /data/local/tmp/mintracker-test-accounts.json
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker rm -f files/live-social-test-accounts.json
    $null=& $adb -s $Device shell appops set se.oxelv.mintracker android:mock_location default
    $accounts=$null;$payload=$null
}

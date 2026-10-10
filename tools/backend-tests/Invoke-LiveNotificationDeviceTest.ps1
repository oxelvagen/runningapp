param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$accounts=Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
$temporary=Join-Path $root "tmp/live-social-accounts-$([Guid]::NewGuid().ToString('N')).json"
$remote='/data/local/tmp/mintracker-live-social-accounts.json'
try {
    $payload=@($accounts)[0..1]|ForEach-Object {
        if ($_.Email -notlike '*@mintracker.example.test') { throw 'Disposable accounts only' }
        @{email=$_.Email;password=$_.Credential.GetNetworkCredential().Password}
    }
    [IO.File]::WriteAllText($temporary,(ConvertTo-Json @($payload) -Compress),[Text.UTF8Encoding]::new($false))
    & $adb -s $Device install -r (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
    & $adb -s $Device install -r (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
    $null=& $adb -s $Device push $temporary $remote
    if ($LASTEXITCODE -ne 0) { throw 'Temporary test provisioning failed' }
    & $adb -s $Device shell run-as se.oxelv.mintracker mkdir -p files
    & $adb -s $Device shell run-as se.oxelv.mintracker cp $remote files/live-social-test-accounts.json
    if ($LASTEXITCODE -ne 0) { throw 'Private test provisioning failed' }
    & $adb -s $Device shell rm $remote
    Remove-Item -LiteralPath $temporary
    $output=& $adb -s $Device shell am instrument -w -r -e class se.oxelv.mintracker.data.LiveNotificationDeviceTest se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    $output | Write-Output
    if (($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Live Android notification test did not pass' }
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    $null=& $adb -s $Device shell rm -f $remote
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker rm -f files/live-social-test-accounts.json
    $accounts=$null;$payload=$null
}

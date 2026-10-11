param([string]$Device='emulator-5554')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$temporary=Join-Path $root "tmp/avatar-login-$([guid]::NewGuid().ToString('N')).json"
try {
    $accounts=Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
    $account=@($accounts)[0]
    if ($account.Email -notlike '*@mintracker.example.test') {throw 'Disposable test accounts only'}
    $payload=@(@{email=$account.Email;password=$account.Credential.GetNetworkCredential().Password})
    [IO.File]::WriteAllText($temporary,(ConvertTo-Json -InputObject $payload -Compress),[Text.UTF8Encoding]::new($false))
    $null=& $adb -s $Device push $temporary /data/local/tmp/mintracker-avatar-test.json
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker mkdir -p files
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker cp /data/local/tmp/mintracker-avatar-test.json files/live-social-test-accounts.json
    $null=& $adb -s $Device shell rm /data/local/tmp/mintracker-avatar-test.json
    Remove-Item -LiteralPath $temporary
    $output=& $adb -s $Device shell am instrument -w -r -e class se.oxelv.mintracker.data.LiveDogAvatarDeviceTest -e dog_avatar_test 1 se.oxelv.mintracker.test/androidx.test.runner.AndroidJUnitRunner
    $output|Write-Output
    if (($output -join "`n") -notmatch 'OK \(1 test\)') {throw 'Actual Android dog-avatar flow failed'}
} finally {
    if (Test-Path -LiteralPath $temporary) {Remove-Item -LiteralPath $temporary}
    $null=& $adb -s $Device shell rm -f /data/local/tmp/mintracker-avatar-test.json
    $null=& $adb -s $Device shell run-as se.oxelv.mintracker rm -f files/live-social-test-accounts.json
    $account=$null;$accounts=$null;$payload=$null
}

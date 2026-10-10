param([switch]$DiagnosticWithoutNote)
$ErrorActionPreference = 'Stop'
$baseUrl = 'https://xbvifoucwpgnybtpwyrk.supabase.co'
$apiKey = 'sb_publishable_OAIeOaKlskUV-3So85QrOQ_XqCK41pi'
$accounts = Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
$sessions = @()
$challengeIds = [Collections.Generic.List[string]]::new()
$activityIds = [Collections.Generic.List[string]]::new()
function Request([string]$path,[string]$method,$body,$session,[int[]]$expected=@(200,201,204)) {
    $headers=@{apikey=$apiKey;Prefer='resolution=merge-duplicates,return=representation'}
    if ($session) { $headers.Authorization="Bearer $($session.access_token)" }
    $parameters=@{Uri="$baseUrl$path";Method=$method;Headers=$headers;SkipHttpErrorCheck=$true}
    if ($null -ne $body) { $parameters.Body=ConvertTo-Json $body -Depth 8 -Compress; $parameters.ContentType='application/json' }
    $response=Invoke-WebRequest @parameters
    if ([int]$response.StatusCode -notin $expected) {
        $code=try { ($response.Content|ConvertFrom-Json).code } catch { 'unknown' }
        throw "Request $method $($path.Split('?')[0]) failed: HTTP $([int]$response.StatusCode), code $code. No credentials logged."
    }
    if ($response.Content) { return ConvertFrom-Json $response.Content }
}
function Check($condition,[string]$message) { if (-not $condition) { throw "FAIL $message" }; Write-Host "PASS $message" }
function Progress([string]$id) {
    $board=@(Request '/rest/v1/rpc/get_challenge_leaderboard' POST @{p_challenge_id=$id} $sessions[0])
    return @($board|Where-Object user_id -eq $sessions[0].user.id)[0]
}
try {
    foreach ($account in @($accounts)[0..1]) {
        if ($account.Email -notlike '*@mintracker.example.test') { throw 'Only named disposable test accounts are allowed' }
        $sessions+=Request '/auth/v1/token?grant_type=password' POST @{
            email=$account.Email;password=$account.Credential.GetNetworkCredential().Password
        } $null
    }
    Check ($sessions.Count -eq 2 -and $sessions[0].user.id -ne $sessions[1].user.id) 'Two distinct real test-account sessions'
    $today=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'Europe/Stockholm').ToString('yyyy-MM-dd')
    foreach ($metric in @('DISTANCE','ACTIVITY_COUNT','DURATION','ELEVATION_GAIN')) {
        $target=@{DISTANCE=10000;ACTIVITY_COUNT=10;DURATION=6000;ELEVATION_GAIN=1000}[$metric]
        $id=Request '/rest/v1/rpc/create_challenge' POST @{
            p_title="Isolated progress audit $metric $([Guid]::NewGuid().ToString('N'))";p_description='Disposable controlled integration test'
            p_metric_type=$metric;p_target_value=$target;p_activity_types=@('RUN');p_start_date=$today;p_end_date=$today
            p_time_zone='Europe/Stockholm';p_scope='PRIVATE';p_group_id=$null
        } $sessions[0]
        $challengeIds.Add([string]$id)
        Check ((Progress $id).progress_value -eq 0) "$metric starts at zero and creator is participant"
    }
    function Add-Activity([double]$distance,[int]$seconds,[double]$elevation,[string]$kind='RUN',[int]$daysAgo=0) {
        $id=[Guid]::NewGuid().ToString(); $activityIds.Add($id)
        $start=[DateTimeOffset]::UtcNow.AddDays(-$daysAgo).AddMinutes(-40)
        $payload=@{id=$id;user_id=$sessions[0].user.id;activity_type=$kind;started_at=$start.ToString('o')
            ended_at=$start.AddSeconds($seconds).ToString('o');duration_seconds=$seconds;distance_meters=$distance
            avg_pace_seconds_per_km=($seconds/($distance/1000));avg_speed_kmh=($distance/$seconds*3.6)
            elevation_gain_meters=$elevation;elevation_loss_meters=0;steps=3000;dog_id=$null}
        if (-not $DiagnosticWithoutNote) { $payload.note='Disposable sync contract audit' }
        $null=Request '/rest/v1/activities' POST $payload $sessions[0]
        $stored=@(Request "/rest/v1/activities?id=eq.$id&select=id,distance_meters" GET $null $sessions[0])
        Check ($stored.Count -eq 1 -and $stored[0].distance_meters -eq $distance) 'Cloud activity read-back confirmed'
        return $id
    }
    $first=Add-Activity 3000 1800 50
    $distance=Progress $challengeIds[0]
    Check ($distance.progress_value -eq 3000 -and $distance.progress_percent -eq 30) 'Distance is 3/10 km and 30 percent'
    $second=Add-Activity 2000 1200 30
    $null=Add-Activity 9000 1000 90 WALK
    $null=Add-Activity 7000 1000 80 RUN 2
    $expected=@(5000,2,3000,80)
    for ($i=0;$i -lt 4;$i++) { Check ((Progress $challengeIds[$i]).progress_value -eq $expected[$i]) "Metric $i includes two qualifying passes only" }
    $null=Request "/rest/v1/activities?id=eq.$first" DELETE $null $sessions[0]
    $expected=@(2000,1,1200,30)
    for ($i=0;$i -lt 4;$i++) { Check ((Progress $challengeIds[$i]).progress_value -eq $expected[$i]) "Metric $i decreases after deletion" }
    $null=Request '/rest/v1/rpc/get_challenge_leaderboard' POST @{p_challenge_id=$challengeIds[0]} $sessions[1] @(403)
    Check $true 'Other test account denied private leaderboard'
    if ($DiagnosticWithoutNote) { Write-Output 'DIAGNOSTIC ONLY: REST summary upload omits missing note column; does not certify Android Room/WorkManager sync.' }
} finally {
    if ($sessions.Count -gt 0) {
        foreach ($id in $activityIds) { $null=Request "/rest/v1/activities?id=eq.$id" DELETE $null $sessions[0] }
        foreach ($id in $challengeIds) { $null=Request '/rest/v1/rpc/delete_challenge' POST @{p_challenge_id=$id} $sessions[0] }
        Write-Output 'Disposable activity and challenge IDs cleaned up.'
    }
    foreach ($session in $sessions) { $null=Request '/auth/v1/logout?scope=local' POST $null $session }
    $sessions=$null; $accounts=$null
}

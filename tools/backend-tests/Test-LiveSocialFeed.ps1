$ErrorActionPreference='Stop'
$baseUrl='https://xbvifoucwpgnybtpwyrk.supabase.co'
$apiKey='sb_publishable_OAIeOaKlskUV-3So85QrOQ_XqCK41pi'
$accounts=Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
$sessions=@(); $activityIds=[Collections.Generic.List[string]]::new(); $challengeIds=[Collections.Generic.List[string]]::new()
$originalPrivacy=$null; $originalPublic=$null; $originalFriend=$null; $friendTouched=$false
function Request([string]$path,[string]$method,$body,$session,[int[]]$expected=@(200,201,204)) {
    $headers=@{apikey=$apiKey;Prefer='resolution=merge-duplicates,return=representation'}
    if ($session) { $headers.Authorization="Bearer $($session.access_token)" }
    $parameters=@{Uri="$baseUrl$path";Method=$method;Headers=$headers;SkipHttpErrorCheck=$true}
    if ($null -ne $body) { $parameters.Body=ConvertTo-Json $body -Depth 12 -Compress; $parameters.ContentType='application/json' }
    $response=Invoke-WebRequest @parameters
    if ([int]$response.StatusCode -notin $expected) {
        $code=try { ($response.Content|ConvertFrom-Json).code } catch { 'unknown' }
        throw "Request $method $($path.Split('?')[0]) failed: HTTP $([int]$response.StatusCode), code $code. No credentials logged."
    }
    if ($response.Content) { return ConvertFrom-Json $response.Content }
}
function Rpc([string]$name,$body,[int]$actor=0,[int[]]$expected=@(200,204)) { Request "/rest/v1/rpc/$name" POST $body $sessions[$actor] $expected }
function Check($condition,[string]$message) { if (-not $condition) { throw "FAIL $message" }; Write-Host "PASS $message" }
function Feed([int]$actor=1) { Rpc get_social_feed @{p_limit=20} $actor }
function OurEvents([int]$actor=1) { @((Feed $actor).items | Where-Object actor_id -eq $sessions[0].user.id) }
function New-Pass([double]$meters,[int]$minutesAgo=90) {
    $id=[Guid]::NewGuid().ToString();$activityIds.Add($id)
    $start=[DateTimeOffset]::UtcNow.AddMinutes(-$minutesAgo)
    return @{id=$id;user_id=$sessions[0].user.id;activity_type='RUN';started_at=$start.ToString('o');ended_at=$start.AddMinutes(20).ToString('o')
        duration_seconds=1200;distance_meters=$meters;elevation_gain_meters=30;elevation_loss_meters=0;steps=2000;dog_id=$null;note='Private live-social audit note'}
}
function Sync($pass,[string]$visibility='PRIVATE',[int]$actor=0) { $null=Rpc sync_social_activity @{p_activity=$pass;p_visibility=$visibility;p_share_dog_name=$false} $actor }
function Connect-Friend {
    $null=Rpc send_friend_request @{p_receiver_id=$sessions[1].user.id}
    $pending=@(Request "/rest/v1/friend_requests?sender_id=eq.$($sessions[0].user.id)&receiver_id=eq.$($sessions[1].user.id)&status=eq.PENDING&select=id" GET $null $sessions[1])
    if ($pending.Count -ne 1) { throw 'Expected one pending disposable friend request' }
    $null=Rpc accept_friend_request @{p_request_id=$pending[0].id} 1
}
function Set-Privacy([bool]$auto,[bool]$statistics) {
    $current=Rpc get_social_privacy @{}
    Rpc set_social_privacy @{p_auto_share=$auto;p_show_statistics=$statistics;p_expected_revision=$current.revision}
}
function Set-Sharing([string]$id,[string]$visibility) {
    $current=Rpc get_activity_sharing @{p_activity_id=$id}
    Rpc set_activity_sharing @{p_activity_id=$id;p_visibility=$visibility;p_share_dog_name=$false;p_expected_revision=$current.revision}
}
try {
    foreach ($account in @($accounts)[0..2]) {
        if ($account.Email -notlike '*@mintracker.example.test') { throw 'Disposable test accounts only' }
        $sessions+=Request '/auth/v1/token?grant_type=password' POST @{email=$account.Email;password=$account.Credential.GetNetworkCredential().Password} $null
    }
    Check (($sessions.user.id|Select-Object -Unique).Count -eq 3) 'Three separate real test-account sessions'
    $actor=$sessions[0].user.id; $friend=$sessions[1].user.id
    $originalPrivacy=Rpc get_social_privacy @{}
    $profile=@(Request "/rest/v1/profiles?id=eq.$actor&select=is_public" GET $null $sessions[0])[0]
    $originalPublic=[bool]$profile.is_public
    $low=@($actor,$friend)|Sort-Object; $friendPath="/rest/v1/friendships?user_low_id=eq.$($low[0])&user_high_id=eq.$($low[1])"
    $originalFriend=@(Request $friendPath GET $null $sessions[0]).Count -gt 0
    if (-not $originalFriend) { Connect-Friend; $friendTouched=$true }
    $null=Set-Privacy $false $false
    $null=Request "/rest/v1/profiles?id=eq.$actor" PATCH @{is_public=$true} $sessions[0]
    Check ((Rpc get_public_profile_statistics @{p_profile_id=$actor} 1).status -eq 'PRIVATE') 'Statistics hidden before opt-in'
    $baseline=New-Pass 1000 120; Sync $baseline
    $pass=New-Pass 3000 90; Sync $pass
    Check ((OurEvents).Count -eq 0) 'Private activities absent from friend feed'
    $null=Set-Privacy $true $true
    Check ((OurEvents).Count -eq 0) 'Enabling automatic sharing does not publish historical private passes'
    $null=Set-Sharing $pass.id FRIENDS
    $visible=@(OurEvents); $event=@($visible|Where-Object event_type -eq ACTIVITY_COMPLETED)[0]
    Check ($null -ne $event -and $event.distance_meters -eq 3000) 'Shared summary visible to accepted friend'
    $json=ConvertTo-Json $visible -Depth 8 -Compress
    foreach ($forbidden in @('Private live-social audit note','latitude','longitude','started_at','ended_at','@mintracker.example.test')) {
        Check (-not $json.Contains($forbidden)) "Feed excludes $forbidden"
    }
    Check (@($visible|Where-Object event_type -eq PERSONAL_RECORD).Count -eq 1) 'Record uses earlier real activity history'
    Check ((OurEvents 2).Count -eq 0) 'Nonfriend cannot see actor events'
    $null=Rpc set_social_cheer @{p_event_id=$event.id;p_cheered=$true} 2 @(403)
    $count=$visible.Count; Sync $pass FRIENDS; Sync $pass PRIVATE
    Check ((OurEvents).Count -eq $count) 'Duplicate sync creates no events and preserves sharing'
    Check ((Rpc set_social_cheer @{p_event_id=$event.id;p_cheered=$true} 1).cheer_count -eq 1) 'Friend can cheer'
    Check ((Rpc set_social_cheer @{p_event_id=$event.id;p_cheered=$true} 1).cheer_count -eq 1) 'Repeated cheer is idempotent'
    Check ((Rpc set_social_cheer @{p_event_id=$event.id;p_cheered=$false} 1).cheer_count -eq 0) 'Friend can remove cheer'
    $stats=Rpc get_public_profile_statistics @{p_profile_id=$actor} 2
    Check ($stats.activity_count -eq 1 -and $stats.distance_meters -eq 3000 -and $stats.run_pace_seconds_per_km -eq 400 -and $stats.weeks.Count -eq 8) 'Public statistics include shared pass only, weighted pace and eight weeks'
    Check ((Rpc get_social_privacy @{} 1).auto_share_new_activities -eq $false) 'Account B does not inherit account A settings'
    $null=Rpc set_activity_sharing @{p_activity_id=$pass.id;p_visibility='PRIVATE';p_share_dog_name=$false;p_expected_revision=1} 1 @(403)
    $null=Rpc sync_social_activity @{p_activity=$pass;p_visibility='FRIENDS'} 1 @(403)
    Check (@(Request "/rest/v1/activities?id=eq.$($pass.id)&select=id" GET $null $sessions[1]).Count -eq 0) 'Friend cannot read raw activity rows'
    $null=Set-Sharing $pass.id PRIVATE
    Check ((OurEvents).Count -eq 0) 'Changing pass to private immediately hides activity and record'
    $null=Set-Sharing $pass.id FRIENDS
    $null=Rpc remove_friend @{p_friend_id=$friend}; $friendTouched=$true
    Check ((OurEvents).Count -eq 0) 'Revoked friendship removes feed access'
    $null=Rpc set_social_cheer @{p_event_id=$event.id;p_cheered=$true} 1 @(403)
    Connect-Friend
    $null=Request "/rest/v1/profiles?id=eq.$actor" PATCH @{is_public=$false} $sessions[0]
    Check ((Rpc get_public_profile_statistics @{p_profile_id=$actor} 1).status -eq 'PRIVATE') 'Private profile statistics hidden even from friend'
    Check (@(Request "/rest/v1/profiles?id=eq.$actor&select=id" GET $null $sessions[2]).Count -eq 0) 'Private profile metadata hidden from nonfriend'
    $null=Request "/rest/v1/profiles?id=eq.$actor" PATCH @{is_public=$true} $sessions[0]
    $null=Set-Privacy $true $false
    Check ((Rpc get_public_profile_statistics @{p_profile_id=$actor} 1).status -eq 'PRIVATE') 'Statistics opt-out takes effect immediately'
    $null=Set-Privacy $true $true
    $day=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'Europe/Stockholm').ToString('yyyy-MM-dd')
    $challenge=Rpc create_challenge @{p_title="Social live audit $([Guid]::NewGuid().ToString('N'))";p_description='Disposable social test';p_metric_type='DISTANCE';p_target_value=10000
        p_activity_types=@('RUN');p_start_date=$day;p_end_date=$day;p_time_zone='Europe/Stockholm';p_scope='FRIENDS';p_group_id=$null}
    $challengeIds.Add([string]$challenge)
    $null=Rpc invite_to_challenge @{p_challenge_id=$challenge;p_receiver_id=$friend}
    $invitations=@(Rpc get_challenge_invitations @{p_sent=$false} 1)
    $invitation=@($invitations|Where-Object challenge_id -eq $challenge)[0]
    $null=Rpc accept_challenge_invitation @{p_invitation_id=$invitation.id} 1
    $goal=New-Pass 10000 40; Sync $goal
    $completion=@((OurEvents)|Where-Object { $_.event_type -eq 'CHALLENGE_COMPLETED' -and $_.challenge_id -eq $challenge })
    Check ($completion.Count -eq 1 -and $null -eq $completion[0].distance_meters -and $null -eq $completion[0].duration_seconds) 'Server completion publishes once without private-pass detail'
    Sync $goal
    Check (@((OurEvents)|Where-Object { $_.event_type -eq 'CHALLENGE_COMPLETED' -and $_.challenge_id -eq $challenge }).Count -eq 1) 'Repeated sync does not duplicate completion'
    $null=Request "/rest/v1/activities?id=eq.$($goal.id)" DELETE $null $sessions[0]
    Check (@((OurEvents)|Where-Object { $_.event_type -eq 'CHALLENGE_COMPLETED' -and $_.challenge_id -eq $challenge }).Count -eq 0) 'Deleting goal pass hides completion'
    $replacement=New-Pass 10000 40; Sync $replacement
    $recross=@((OurEvents)|Where-Object { $_.event_type -eq 'CHALLENGE_COMPLETED' -and $_.challenge_id -eq $challenge })
    Check ($recross.Count -eq 1 -and $recross[0].id -eq $completion[0].id) 'Re-crossing goal reuses the same completion event'
    Write-Host 'PASS live social REST integration complete; Android/offline device verification is separate.'
} finally {
    if ($sessions.Count -ge 2) {
        foreach ($id in $activityIds) { $null=Request "/rest/v1/activities?id=eq.$id" DELETE $null $sessions[0] }
        foreach ($id in $challengeIds) { $null=Rpc delete_challenge @{p_challenge_id=$id} }
        if ($null -ne $originalPrivacy) { $null=Set-Privacy $originalPrivacy.auto_share_new_activities $originalPrivacy.show_profile_statistics }
        if ($null -ne $originalPublic) { $null=Request "/rest/v1/profiles?id=eq.$($sessions[0].user.id)" PATCH @{is_public=$originalPublic} $sessions[0] }
        if ($friendTouched -and $null -ne $originalFriend) {
            $present=@(Request $friendPath GET $null $sessions[0]).Count -gt 0
            if ($originalFriend -and -not $present) { Connect-Friend }
            if (-not $originalFriend -and $present) { $null=Rpc remove_friend @{p_friend_id=$sessions[1].user.id} }
        }
        Write-Host 'Temporary social activities/challenges removed; profile settings and friendship restored.'
    }
    foreach ($session in $sessions) { $null=Request '/auth/v1/logout?scope=local' POST $null $session }
    $sessions=$null;$accounts=$null
}

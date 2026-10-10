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
function Notices([int]$actor=1) { Rpc get_notification_center @{p_limit=50} $actor }
function Target-Notices([string]$target,[int]$actor=1) { @((Notices $actor).items | Where-Object target_id -eq $target) }
$originalPreferences=@();$groupId=$null;$createdRequest=$null
try {
    foreach ($account in @($accounts)[0..2]) {
        if ($account.Email -notlike '*@mintracker.example.test') { throw 'Disposable test accounts only' }
        $sessions+=Request '/auth/v1/token?grant_type=password' POST @{email=$account.Email;password=$account.Credential.GetNetworkCredential().Password} $null
    }
    Check (($sessions.user.id|Select-Object -Unique).Count -eq 3) 'Three real accounts authenticated without logging credentials'
    $actor=$sessions[0].user.id;$friend=$sessions[1].user.id
    $originalPrivacy=Rpc get_social_privacy @{}
    foreach($i in 0..2) {
        $pref=Rpc get_notification_preferences @{} $i;$originalPreferences+=,$pref
        $all=@{};foreach($entry in $pref.categories.psobject.Properties) { $all[$entry.Name]=$true }
        $null=Rpc set_notification_preferences @{p_categories=$all;p_expected_revision=$pref.revision} $i
    }
    $low=@($actor,$friend)|Sort-Object;$friendPath="/rest/v1/friendships?user_low_id=eq.$($low[0])&user_high_id=eq.$($low[1])"
    $originalFriend=@(Request $friendPath GET $null $sessions[0]).Count -gt 0
    if(-not $originalFriend) { Connect-Friend;$friendTouched=$true }
    $null=Set-Privacy $false $false
    $history=@(Request "/rest/v1/activities?user_id=eq.$actor&activity_type=in.(RUN,L%C3%B6pning)&select=distance_meters&order=distance_meters.desc&limit=1" GET $null $sessions[0])
    $recordDistance=if($history.Count) { [Math]::Max(5000,[double]$history[0].distance_meters+1000) } else {5000}
    $baseline=New-Pass 1000 120;Sync $baseline
    $pass=New-Pass $recordDistance 90;Sync $pass
    Check ((Target-Notices $pass.id).Count -eq 0) 'PRIVATE activity produces no friend notification'
    $null=Set-Sharing $pass.id FRIENDS
    $visible=Target-Notices $pass.id
    Check (@($visible|Where-Object event_type -eq ACTIVITY_COMPLETED).Count -eq 1) 'Shared activity produces one friend notification'
    Check (@($visible|Where-Object event_type -eq PERSONAL_RECORD).Count -eq 1) 'Real longer RUN produces one record notification'
    Check ((Target-Notices $pass.id 0).Count -eq 0) 'No self-notification for activity or record'
    Check ((Target-Notices $pass.id 2).Count -eq 0) 'Nonfriend sees no private friend notifications'
    $notification=@($visible|Where-Object event_type -eq ACTIVITY_COMPLETED)[0]
    $payload=ConvertTo-Json (Rpc get_notification_activity @{p_activity_id=$pass.id} 1) -Depth 10 -Compress
    foreach($key in @('Private live-social audit note','latitude','longitude','started_at','ended_at','@mintracker.example.test')) { Check (-not $payload.Contains($key)) "Shared detail excludes $key" }
    $null=Rpc get_notification_activity @{p_activity_id=$pass.id} 2 @(403)
    $before=(Notices).unread_count
    $opened=Rpc read_notification @{p_id=$notification.id} 1
    Check ($opened.available -and $opened.target_id -eq $pass.id -and $opened.unread_count -eq $before-1) 'Opening marks read and lowers badge count'
    $again=Rpc read_notification @{p_id=$notification.id} 1
    Check ($again.unread_count -eq $opened.unread_count) 'Repeated read is idempotent'
    Check (-not (Rpc read_notification @{p_id=$notification.id} 2).available) 'Other account cannot open or mark recipient notification'
    $ids=@($visible.id|Sort-Object);Sync $pass FRIENDS;Sync $pass PRIVATE
    Check ((Compare-Object $ids @((Target-Notices $pass.id).id|Sort-Object)).Count -eq 0) 'Repeated WorkManager-style sync preserves notification IDs'
    $event=$notification.payload.social_event_id
    $null=Rpc set_social_cheer @{p_event_id=$event;p_cheered=$true} 1
    $null=Rpc set_social_cheer @{p_event_id=$event;p_cheered=$true} 1
    Check (@((Target-Notices $pass.id 0)|Where-Object event_type -eq CHEER).Count -eq 1) 'Cheer produces one owner notification'
    $null=Rpc set_social_cheer @{p_event_id=$event;p_cheered=$false} 1
    Check (@((Target-Notices $pass.id 0)|Where-Object event_type -eq CHEER).Count -eq 0) 'Removed cheer disappears consistently'
    $pref=Rpc get_notification_preferences @{} 1
    $choices=@{};foreach($p in $pref.categories.psobject.Properties){$choices[$p.Name]=$p.Value};$choices.records=$false
    $null=Rpc set_notification_preferences @{p_categories=$choices;p_expected_revision=$pref.revision} 1
    Check (@((Target-Notices $pass.id)|Where-Object event_type -eq PERSONAL_RECORD).Count -eq 0) 'Individual category disables record notifications'
    Check ((Rpc get_notification_preferences @{}).categories.records) 'Account preferences do not leak between accounts'
    $pref=Rpc get_notification_preferences @{} 1;$choices.records=$true
    $null=Rpc set_notification_preferences @{p_categories=$choices;p_expected_revision=$pref.revision} 1
    $null=Set-Sharing $pass.id PRIVATE
    Check ((Target-Notices $pass.id).Count -eq 0) 'Privacy revokes inbox and read target'
    Check (-not (Rpc read_notification @{p_id=$notification.id} 1).available) 'Old notification cannot bypass changed sharing'
    $null=Set-Sharing $pass.id FRIENDS
    $null=Rpc remove_friend @{p_friend_id=$friend};$friendTouched=$true
    Check ((Target-Notices $pass.id).Count -eq 0) 'Friend removal revokes protected notification access'
    $null=Rpc get_notification_activity @{p_activity_id=$pass.id} 1 @(403)
    $null=Rpc send_friend_request @{p_receiver_id=$friend}
    $pending=@(Request "/rest/v1/friend_requests?sender_id=eq.$actor&receiver_id=eq.$friend&status=eq.PENDING&select=id" GET $null $sessions[1]);$createdRequest=$pending[0].id
    Check (@((Notices).items|Where-Object { $_.event_type -eq 'FRIEND_REQUEST' -and $_.target_id -eq $createdRequest }).Count -eq 1) 'New friend request has a notification'
    $null=Rpc accept_friend_request @{p_request_id=$createdRequest} 1
    Check (@((Notices 0).items|Where-Object { $_.event_type -eq 'FRIEND_ACCEPTED' -and $_.actor_id -eq $friend }).Count -ge 1) 'Accepted friend request notifies sender'
    $day=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'Europe/Stockholm').ToString('yyyy-MM-dd')
    foreach($visibility in @('PRIVATE','FRIENDS')) {
        $challenge=Rpc create_challenge @{p_title="Notification audit $([Guid]::NewGuid().ToString('N'))";p_description='Disposable test';p_metric_type='DISTANCE';p_target_value=100000
          p_activity_types=@('RUN');p_start_date=$day;p_end_date=$day;p_time_zone='Europe/Stockholm';p_scope=$visibility;p_group_id=$null}
        $challengeIds.Add([string]$challenge)
        Check ((Target-Notices $challenge).Count -eq 0) "$visibility challenge has no notification before authorized access"
        if($visibility -eq 'FRIENDS') {
            $null=Rpc invite_to_challenge @{p_challenge_id=$challenge;p_receiver_id=$friend}
            Check (@((Target-Notices $challenge)|Where-Object event_type -eq CHALLENGE_CREATED).Count -eq 1) 'Allowed friend challenge is notified'
            Check ((Target-Notices $challenge 2).Count -eq 0) 'Uninvited account cannot see challenge notification'
            $invitations=@(Rpc get_challenge_invitations @{p_sent=$false} 1)
            $invitation=@($invitations|Where-Object challenge_id -eq $challenge)[0]
            $null=Rpc accept_challenge_invitation @{p_invitation_id=$invitation.id} 1
            $null=Set-Privacy $true $false
            $goal=New-Pass 100000 40;Sync $goal PRIVATE
            $completion=@((Target-Notices $challenge)|Where-Object event_type -eq CHALLENGE_COMPLETED)
            Check ($completion.Count -eq 1) 'Server-confirmed goal produces one authorized completion notification'
            Sync $goal PRIVATE
            Check (@((Target-Notices $challenge)|Where-Object event_type -eq CHALLENGE_COMPLETED).Count -eq 1) 'Retried goal sync produces no duplicate completion'
            $null=Request "/rest/v1/activities?id=eq.$($goal.id)" DELETE $null $sessions[0]
            Check (@((Target-Notices $challenge)|Where-Object event_type -eq CHALLENGE_COMPLETED).Count -eq 0) 'Deleted goal activity revokes completion notification'
        }
    }
    $code=-join ((1..6)|ForEach-Object { 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'[(Get-Random -Maximum 32)] })
    $groupId=Rpc create_group @{p_name="Notification audit $([Guid]::NewGuid().ToString('N'))";p_group_type='GROUP';p_invite_code=$code}
    $null=Rpc join_group_by_invite_code @{p_invite_code=$code} 1
    Check (@((Target-Notices $groupId)|Where-Object event_type -eq GROUP_JOINED).Count -eq 1) 'Member receives own group-join notification'
    Check (@((Target-Notices $groupId 0)|Where-Object event_type -eq GROUP_MEMBER_JOINED).Count -eq 1) 'Group owner receives new-member notification'
    Check ((Target-Notices $groupId 2).Count -eq 0) 'Unrelated account receives no group notification'
    $null=Rpc leave_group @{p_group_id=$groupId} 1
    Check ((Target-Notices $groupId).Count -eq 0) 'Leaving group removes group target from inbox'
    $null=Rpc read_all_notifications @{} 1
    Check ((Rpc get_notification_unread_count @{} 1) -eq 0) 'Mark all read reduces current badge to zero'
    $null=Request "/rest/v1/activities?id=eq.$($pass.id)" DELETE $null $sessions[0]
    Check ((Target-Notices $pass.id).Count -eq 0) 'Deleted activity removes notification'
    $null=Rpc get_notification_activity @{p_activity_id=$pass.id} 1 @(403)
    Write-Host 'PASS live notification integration. No production-user data touched.'
} finally {
    if($sessions.Count -ge 2) {
        foreach($id in $activityIds) { $null=Request "/rest/v1/activities?id=eq.$id" DELETE $null $sessions[0] }
        foreach($id in $challengeIds) { $null=Rpc delete_challenge @{p_challenge_id=$id} }
        if($groupId) { $null=Rpc delete_group @{p_group_id=$groupId} }
        if($null -ne $originalPrivacy) { $null=Set-Privacy $originalPrivacy.auto_share_new_activities $originalPrivacy.show_profile_statistics }
        foreach($i in 0..($originalPreferences.Count-1)) {
            if($i -ge 0 -and $i -lt $originalPreferences.Count) { $current=Rpc get_notification_preferences @{} $i
                $null=Rpc set_notification_preferences @{p_categories=$originalPreferences[$i].categories;p_expected_revision=$current.revision} $i }
        }
        if($friendTouched -and $null -ne $originalFriend) {
            $present=@(Request $friendPath GET $null $sessions[0]).Count -gt 0
            if($originalFriend -and -not $present) { Connect-Friend }
            if(-not $originalFriend -and $present) { $null=Rpc remove_friend @{p_friend_id=$friend} }
        }
        Write-Host 'Temporary activities, challenges and group removed; settings and friendship restored.'
    }
    foreach($session in $sessions) { $null=Request '/auth/v1/logout?scope=local' POST $null $session }
    $sessions=$null;$accounts=$null
}

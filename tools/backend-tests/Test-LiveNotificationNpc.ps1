$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '../..')
$accounts=Import-Clixml -LiteralPath (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'MinTracker-TestUsers.xml')
if(@($accounts)[0].Email -notlike '*@mintracker.example.test' -or @($accounts)[1].Email -notlike '*@mintracker.example.test') { throw 'Disposable accounts only' }
$actor=[Guid]::Parse(@($accounts)[0].Id).ToString()
$viewer=[Guid]::Parse(@($accounts)[1].Id).ToString()
$activity=[Guid]::NewGuid().ToString()
$sql=@"
begin;
update public.app_settings set npc_notifications_enabled=true;
update public.profiles set account_type='NPC' where id='$actor';
insert into public.npc_profiles(profile_id,seed) values('$actor',19) on conflict(profile_id) do nothing;
insert into public.friendships(user_low_id,user_high_id) values(least('$actor'::uuid,'$viewer'::uuid),greatest('$actor'::uuid,'$viewer'::uuid)) on conflict do nothing;
insert into public.activities(id,user_id,activity_type,started_at,ended_at,duration_seconds,distance_meters)
 values('$activity','$actor','RUN',now()-interval '30 minutes',now()-interval '1 second',1799,5200);
update public.activity_sharing set visibility='FRIENDS' where activity_id='$activity';
set local role authenticated;
select set_config('request.jwt.claim.sub','$viewer',true),set_config('request.jwt.claim.role','authenticated',true);
do `$test`$ declare entry jsonb; begin
 select x into entry from jsonb_array_elements(public.get_notification_center(false,null,null,50)->'items') x
  where x->>'target_id'='$activity' and x->>'event_type'='ACTIVITY_COMPLETED';
 if entry is null or not (entry->'payload'->>'is_npc')::boolean then raise exception 'NPC must be visible and explicitly labelled'; end if;
 if not (public.get_notification_activity('$activity')->>'is_npc')::boolean then raise exception 'NPC detail label missing'; end if;
end `$test`$;
reset role;
update public.activity_sharing set visibility='PRIVATE' where activity_id='$activity';
set local role authenticated;
do `$test`$ begin
 if exists(select 1 from jsonb_array_elements(public.get_notification_center(false,null,null,50)->'items') x where x->>'target_id'='$activity') then raise exception 'Private NPC activity leaked'; end if;
end `$test`$;
reset role;
update public.activity_sharing set visibility='FRIENDS' where activity_id='$activity';
delete from public.friendships where user_low_id=least('$actor'::uuid,'$viewer'::uuid) and user_high_id=greatest('$actor'::uuid,'$viewer'::uuid);
set local role authenticated;
do `$test`$ begin
 if exists(select 1 from jsonb_array_elements(public.get_notification_center(false,null,null,50)->'items') x where x->>'target_id'='$activity') then raise exception 'Nonfriend NPC activity leaked'; end if;
end `$test`$;
reset role;
rollback;
select 'PASS real PostgreSQL NPC label, safe detail, private activity and revoked friendship. Transaction rolled back.' result;
"@
$path=Join-Path $root 'tmp/notification-npc-rollback.sql'
try {
    [IO.File]::WriteAllText($path,$sql)
    & "$env:LOCALAPPDATA/MinTracker/bin/supabase.exe" db query --linked --file $path
    if($LASTEXITCODE -ne 0) { throw 'Live NPC transaction test failed' }
} finally { if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }; $accounts=$null }

-- Reviewed proposal approved by the user and applied to the linked project on 2026-10-11.
-- No existing activity/auth/social policy is replaced. Uses the existing admin role.
begin;

create table public.app_error_groups (
  fingerprint text primary key check (fingerprint ~ '^[a-f0-9]{32}$'),
  category text not null,
  code text not null,
  feature text not null,
  severity text not null,
  status text not null default 'NEW' check (status in ('NEW','INVESTIGATING','RESOLVED','IGNORED')),
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  occurrences bigint not null default 0,
  status_updated_at timestamptz,
  status_updated_by uuid references auth.users(id) on delete set null
);
create table public.app_error_reports (
  id uuid primary key,
  fingerprint text not null references public.app_error_groups(fingerprint) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  installation_id uuid not null,
  occurred_at timestamptz not null,
  received_at timestamptz not null default now(),
  version_name text not null check (length(version_name) between 1 and 32),
  version_code integer not null check (version_code between 1 and 1000000),
  android_api integer not null check (android_api between 26 and 100),
  device_model text not null check (length(device_model) between 1 and 80),
  foreground boolean not null,
  training boolean not null,
  frames jsonb not null check (jsonb_typeof(frames)='array' and jsonb_array_length(frames)<=20),
  occurrences integer not null check (occurrences between 1 and 1000)
);
create index app_error_reports_recent on public.app_error_reports(received_at desc,id);
create index app_error_reports_owner_rate on public.app_error_reports(user_id,received_at);
create index app_error_reports_group on public.app_error_reports(fingerprint,received_at desc);
create index app_error_groups_recent on public.app_error_groups(last_seen desc,fingerprint);

alter table public.app_error_groups enable row level security;
alter table public.app_error_reports enable row level security;
revoke all on public.app_error_groups,public.app_error_reports from public,anon,authenticated;
grant select on public.app_error_groups,public.app_error_reports to authenticated;
create policy app_error_groups_admin_read on public.app_error_groups for select to authenticated using(public.is_admin());
create policy app_error_reports_admin_read on public.app_error_reports for select to authenticated using(public.is_admin());

-- No free-form messages, URLs, HTTP bodies or exception messages are accepted.
-- Stack frames are app class/method identifiers only; source line/file and library messages are omitted.
create function public.submit_app_error_reports(p_reports jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare
  v_user uuid:=auth.uid(); r jsonb; v_id uuid; v_code text; v_category text; v_feature text;
  v_severity text; v_frames jsonb; v_frame text; v_fingerprint text; v_result jsonb:='[]'; v_time timestamptz;
  v_occurrences integer; v_model text; v_version text; v_count integer;
begin
  if v_user is null or not exists(select 1 from public.profiles where id=v_user and not banned and archived_at is null)
    then raise exception 'Active account required' using errcode='42501'; end if;
  if jsonb_typeof(p_reports)<>'array' or jsonb_array_length(p_reports) not between 1 and 20
    or octet_length(p_reports::text)>32768 then raise exception 'Invalid diagnostic batch' using errcode='22023'; end if;
  -- Serializes quota and idempotency checks across parallel submissions for this account.
  perform pg_advisory_xact_lock(hashtextextended('diagnostics:'||v_user::text,0));
  for r in select value from jsonb_array_elements(p_reports) loop
    if jsonb_typeof(r)<>'object' or exists(select 1 from jsonb_object_keys(r) k where k not in
      ('id','installation_id','occurred_at','category','code','feature','severity','version_name','version_code','android_api','device_model','foreground','training','frames','occurrences'))
      then raise exception 'Unexpected diagnostic field' using errcode='22023'; end if;
    v_id:=(r->>'id')::uuid;
    if v_id is null then raise exception 'Diagnostic id required' using errcode='22023'; end if;
    if exists(select 1 from public.app_error_reports where id=v_id and user_id=v_user) then
      v_result:=v_result||jsonb_build_array(v_id); continue;
    elsif exists(select 1 from public.app_error_reports where id=v_id) then
      raise exception 'Diagnostic identity conflict' using errcode='22023';
    end if;
    select count(*) into v_count from public.app_error_reports where user_id=v_user and received_at>now()-interval '24 hours';
    if v_count>=60 or (select count(*) from public.app_error_reports where user_id=v_user and received_at>now()-interval '1 minute')>=20
      then raise exception 'Diagnostic rate limit' using errcode='53000'; end if;
    v_code:=r->>'code'; v_category:=r->>'category'; v_feature:=r->>'feature'; v_severity:=r->>'severity';
    if v_code is null or v_code not in ('UNCAUGHT_EXCEPTION','PROCESS_CRASH','PROCESS_ANR','PROCESS_INTERRUPTED',
      'AUTH_REFRESH_FAILED','AUTH_UNLOCK_FAILED','GPS_START_FAILED','GPS_SIGNAL_LOST','GPS_SERVICE_STOPPED',
      'ACTIVITY_SAVE_FAILED','ACTIVITY_RESTORE_FAILED','SYNC_FAILED','PROFILE_IMAGE_UPLOAD_FAILED','IMAGE_LOAD_FAILED',
      'DATABASE_FAILED','CHALLENGE_CREATE_FAILED','CHEER_FAILED','UPDATE_FAILED',
      'COUNTDOWN_STARTED','SERVICE_STARTED','GPS_STARTED','GPS_FIRST_FIX','SESSION_RESTORED','ACTIVITY_SAVED','SERVICE_STOPPED')
      or v_category is null or v_category not in ('CRASH','AUTH','GPS','ACTIVITY','SYNC','STORAGE','NETWORK','DATABASE','SOCIAL','CHALLENGE','UI','UPDATE','UNKNOWN')
      or v_feature is null or v_feature not in ('TRAINING','AUTH','SYNC','PROFILE','IMAGE','DATABASE','CHALLENGE','SOCIAL','UPDATE','APPLICATION')
      or v_severity is null or v_severity not in ('INFO','WARNING','ERROR','CRITICAL')
      then raise exception 'Invalid diagnostic classification' using errcode='22023'; end if;
    if (v_code in ('COUNTDOWN_STARTED','SERVICE_STARTED','GPS_STARTED','GPS_FIRST_FIX','SESSION_RESTORED','ACTIVITY_SAVED','SERVICE_STOPPED'))
      is distinct from (v_severity='INFO') then raise exception 'Invalid diagnostic level' using errcode='22023'; end if;
    v_frames:=r->'frames';
    if v_frames is null or jsonb_typeof(v_frames)<>'array' or jsonb_array_length(v_frames)>20 then
      raise exception 'Invalid frames' using errcode='22023'; end if;
    for v_frame in select jsonb_array_elements_text(v_frames) loop
      if v_frame is null or length(v_frame)>180 or v_frame !~ '^se[.]oxelv[.]mintracker[.][A-Za-z0-9_.$]+#[A-Za-z0-9_$<>]+$' then
        raise exception 'Unsafe frame' using errcode='22023'; end if;
    end loop;
    v_model:=r->>'device_model'; v_version:=r->>'version_name';
    if v_model is null or v_model !~ '^[A-Za-z0-9 _().-]{1,80}$' or v_version is null or v_version !~ '^[0-9][A-Za-z0-9.+_-]{0,31}$'
      or jsonb_typeof(r->'foreground') is distinct from 'boolean' or jsonb_typeof(r->'training') is distinct from 'boolean'
      then raise exception 'Invalid device metadata' using errcode='22023'; end if;
    v_time:=(r->>'occurred_at')::timestamptz;
    if v_time is null or v_time<now()-interval '30 days' or v_time>now()+interval '5 minutes' then
      raise exception 'Invalid diagnostic timestamp' using errcode='22023'; end if;
    v_occurrences:=(r->>'occurrences')::integer;
    v_fingerprint:=md5(v_category||':'||v_code||':'||v_feature||':'||v_frames::text);
    insert into public.app_error_groups(fingerprint,category,code,feature,severity,occurrences)
      values(v_fingerprint,v_category,v_code,v_feature,v_severity,v_occurrences)
      on conflict(fingerprint) do update set last_seen=now(),occurrences=app_error_groups.occurrences+excluded.occurrences,
        severity=case when array_position(array['INFO','WARNING','ERROR','CRITICAL'],excluded.severity)>
          array_position(array['INFO','WARNING','ERROR','CRITICAL'],app_error_groups.severity) then excluded.severity else app_error_groups.severity end;
    insert into public.app_error_reports(id,fingerprint,user_id,installation_id,occurred_at,version_name,version_code,android_api,device_model,foreground,training,frames,occurrences)
      values(v_id,v_fingerprint,v_user,(r->>'installation_id')::uuid,v_time,v_version,(r->>'version_code')::integer,
        (r->>'android_api')::integer,v_model,(r->>'foreground')::boolean,(r->>'training')::boolean,v_frames,v_occurrences);
    v_result:=v_result||jsonb_build_array(v_id);
  end loop;
  return jsonb_build_object('accepted_ids',v_result);
end $$;

create function public.admin_system_health(p_page integer default 0,p_query text default '',p_category text default 'ALL',p_severity text default 'ALL',p_status text default 'ALL')
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
declare v_result jsonb;
begin
  perform public._require_admin();
  if p_page not between 0 and 200 or length(p_query)>64 then raise exception 'Invalid page' using errcode='22023'; end if;
  select jsonb_build_object(
    'errors_24h',coalesce(sum(r.occurrences) filter(where g.severity<>'INFO'),0),
    'crashes_24h',coalesce(sum(r.occurrences) filter(where g.category='CRASH' and g.code in ('UNCAUGHT_EXCEPTION','PROCESS_CRASH')),0),
    'affected_users_24h',count(distinct r.user_id) filter(where g.severity<>'INFO'),
    'critical_24h',coalesce(sum(r.occurrences) filter(where g.severity='CRITICAL'),0)) into v_result
    from public.app_error_reports r join public.app_error_groups g using(fingerprint) where r.received_at>now()-interval '24 hours';
  return v_result||jsonb_build_object(
    'by_version',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select r.version_name,r.version_code,sum(r.occurrences) occurrences from public.app_error_reports r join public.app_error_groups g using(fingerprint) where r.received_at>now()-interval '24 hours' and g.severity<>'INFO' group by r.version_name,r.version_code order by occurrences desc) x),
    'by_android',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select r.android_api,sum(r.occurrences) occurrences from public.app_error_reports r join public.app_error_groups g using(fingerprint) where r.received_at>now()-interval '24 hours' and g.severity<>'INFO' group by r.android_api order by occurrences desc) x),
    'by_category',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select g.category,sum(r.occurrences) occurrences from public.app_error_reports r join public.app_error_groups g using(fingerprint) where r.received_at>now()-interval '24 hours' and g.severity<>'INFO' group by g.category order by occurrences desc) x),
    'groups',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select g.*,
      (select r.device_model from public.app_error_reports r where r.fingerprint=g.fingerprint order by received_at desc limit 1) device_model,
      (select r.version_name from public.app_error_reports r where r.fingerprint=g.fingerprint order by received_at desc limit 1) version_name
      from public.app_error_groups g where (p_category='ALL' or g.category=p_category) and (p_severity='ALL' or g.severity=p_severity)
      and (p_status='ALL' or g.status=p_status) and (p_query='' or g.code ilike '%'||p_query||'%')
      order by g.last_seen desc,g.fingerprint limit 50 offset p_page*50) x));
end $$;

create function public.admin_system_health_detail(p_fingerprint text) returns jsonb
language plpgsql stable security definer set search_path=public,pg_temp as $$
begin
  perform public._require_admin();
  return jsonb_build_object('group',(select to_jsonb(g) from public.app_error_groups g where fingerprint=p_fingerprint),
    'versions',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select version_name,version_code,sum(occurrences) occurrences from public.app_error_reports where fingerprint=p_fingerprint group by version_name,version_code) x),
    'reports',(select coalesce(jsonb_agg(to_jsonb(x)),'[]') from (select * from public.app_error_reports where fingerprint=p_fingerprint order by received_at desc,id limit 100) x));
end $$;

create function public.admin_system_health_status(p_fingerprint text,p_status text) returns void
language plpgsql security definer set search_path=public,pg_temp as $$
begin
  perform public._require_admin();
  if p_status not in ('NEW','INVESTIGATING','RESOLVED','IGNORED') or p_status is null then
    raise exception 'Invalid diagnostic status' using errcode='22023'; end if;
  update public.app_error_groups set status=p_status,status_updated_at=now(),status_updated_by=auth.uid() where fingerprint=p_fingerprint;
  if not found then raise exception 'Diagnostic group unavailable' using errcode='22023'; end if;
  insert into public.admin_audit_log(admin_user_id,action,metadata)
    values(auth.uid(),'SYSTEM_HEALTH_STATUS',jsonb_build_object('fingerprint',p_fingerprint,'status',p_status));
end $$;

create function public.purge_app_diagnostics() returns void language sql security definer set search_path=public,pg_temp as $$
  delete from public.app_error_reports where received_at<now()-interval '30 days';
  delete from public.app_error_groups where last_seen<now()-interval '90 days';
$$;
revoke all on function public.submit_app_error_reports(jsonb),public.admin_system_health(integer,text,text,text,text),public.admin_system_health_detail(text),public.admin_system_health_status(text,text),public.purge_app_diagnostics() from public,anon,authenticated;
grant execute on function public.submit_app_error_reports(jsonb),public.admin_system_health(integer,text,text,text,text),public.admin_system_health_detail(text),public.admin_system_health_status(text,text) to authenticated;
grant execute on function public.purge_app_diagnostics() to service_role;
-- Existing project already uses pg_cron. Test harness may not have the extension installed.
do $$ begin
  if exists(select 1 from pg_namespace where nspname='cron') then
    execute $job$select cron.schedule('mintracker-diagnostics-retention','40 3 * * *','select public.purge_app_diagnostics()')$job$;
  end if;
end $$;
commit;

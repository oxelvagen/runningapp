-- Historical review snapshot, approved/applied 2026-10-10. Canonical copy is in migrations/.
-- No existing pass is shared by this migration. No GPS/notes in any public result.
begin;

create table public.social_privacy_settings (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  auto_share_new_activities boolean not null default false,
  show_profile_statistics boolean not null default false,
  share_after timestamptz,
  revision bigint not null default 0,
  updated_at timestamptz not null default now()
);
create table public.activity_sharing (
  activity_id uuid primary key references public.activities(id) on delete cascade,
  owner_id uuid not null references public.profiles(id) on delete cascade,
  visibility text not null default 'PRIVATE' check (visibility in ('PRIVATE','FRIENDS')),
  share_dog_name boolean not null default false,
  revision bigint not null default 0,
  updated_at timestamptz not null default now()
);
create index activity_sharing_owner on public.activity_sharing(owner_id,visibility,activity_id);
create table public.social_challenge_completions (
  challenge_id uuid not null,
  user_id uuid not null,
  reached boolean not null default false,
  first_reached_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key(challenge_id,user_id),
  foreign key(challenge_id,user_id) references public.challenge_participants(challenge_id,user_id) on delete cascade
);
create table public.social_events (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references public.profiles(id) on delete cascade,
  event_type text not null check (event_type in ('ACTIVITY_COMPLETED','CHALLENGE_COMPLETED','PERSONAL_RECORD')),
  activity_id uuid references public.activities(id) on delete cascade,
  challenge_id uuid,
  consent_since timestamptz,
  occurred_at timestamptz not null,
  check ((event_type in ('ACTIVITY_COMPLETED','PERSONAL_RECORD') and activity_id is not null and challenge_id is null)
    or (event_type='CHALLENGE_COMPLETED' and activity_id is null and challenge_id is not null and consent_since is not null)),
  foreign key(challenge_id,actor_id) references public.social_challenge_completions(challenge_id,user_id) on delete cascade
);
create unique index social_events_activity_unique on public.social_events(actor_id,activity_id,event_type) where activity_id is not null;
create unique index social_events_challenge_unique on public.social_events(actor_id,challenge_id,event_type) where challenge_id is not null;
create index social_events_actor_cursor on public.social_events(actor_id,occurred_at desc,id desc);
create table public.social_cheers (
  event_id uuid not null references public.social_events(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(event_id,user_id)
);
create index social_cheers_user on public.social_cheers(user_id,event_id);
create index if not exists social_activity_owner_time on public.activities(user_id,started_at,id);

alter table public.social_privacy_settings enable row level security;
alter table public.activity_sharing enable row level security;
alter table public.social_challenge_completions enable row level security;
alter table public.social_events enable row level security;
alter table public.social_cheers enable row level security;
-- No client table access, including owner writes. RPCs are the only contract.
revoke all on public.social_privacy_settings,public.activity_sharing,public.social_challenge_completions,
  public.social_events,public.social_cheers from public,anon,authenticated;

create function public._social_require_actor() returns uuid language plpgsql stable security definer
set search_path=public,pg_temp as $$ begin
  if not public._account_active() then raise exception 'Account unavailable' using errcode='42501'; end if;
  return auth.uid();
end $$;
create function public._social_friends(a uuid,b uuid) returns boolean language sql stable security definer
set search_path=public,pg_temp as $$
  select a<>b and exists(select 1 from public.friendships f where f.user_low_id=least(a,b) and f.user_high_id=greatest(a,b));
$$;
create function public._social_kind(value text) returns text language sql immutable as $$
  select case value when 'Löpning' then 'RUN' when 'Promenad' then 'WALK' else value end;
$$;
-- Strictly longer RUN (not DOG_RUN). First ever run is a baseline, never a record event.
create function public._social_is_record(p_activity uuid) returns boolean language sql stable security definer
set search_path=public,pg_temp as $$
  select coalesce((select public._social_kind(a.activity_type)='RUN' and a.distance_meters>0
    and exists(select 1 from public.activities prev where prev.user_id=a.user_id and public._social_kind(prev.activity_type)='RUN'
      and (prev.started_at,prev.id)<(a.started_at,a.id))
    and not exists(select 1 from public.activities prev where prev.user_id=a.user_id and public._social_kind(prev.activity_type)='RUN'
      and (prev.started_at,prev.id)<(a.started_at,a.id) and prev.distance_meters>=a.distance_meters)
    from public.activities a where a.id=p_activity),false);
$$;

-- Same metric and [start,end) rules as _challenge_progress, scoped to one participant.
-- Shared-only mode is used for VISITOR statistics, never for actual challenge ranking.
create function public._social_challenge_value(p_challenge uuid,p_user uuid,p_shared_only boolean default false)
returns numeric language sql stable security definer set search_path=public,pg_temp as $$
  select coalesce(case c.metric_type
    when 'LONGEST_ACTIVITY' then max(greatest(a.distance_meters,0))::numeric
    when 'FASTEST_5K' then max(five.speed)
    else sum(case when a.id is null then 0 else case c.metric_type
      when 'DISTANCE' then greatest(a.distance_meters,0)::numeric
      when 'DURATION' then greatest(a.duration_seconds,0)::numeric
      when 'ELEVATION_GAIN' then greatest(coalesce(a.elevation_gain_meters,0),0)::numeric
      when 'ACTIVITY_COUNT' then 1::numeric else 0::numeric end end) end,0)::numeric
  from public.challenges c join public.challenge_participants cp on cp.challenge_id=c.id and cp.user_id=p_user
  left join public.activities a on a.user_id=cp.user_id and a.started_at>=c.start_at
    and a.started_at<least(c.end_at,coalesce(c.closed_at,c.end_at)) and a.started_at<now()
    and (c.metric_type<>'FASTEST_5K' or (a.ended_at<=now() and a.distance_meters>=5000))
    and public._social_kind(a.activity_type) in (select t.activity_type from public.challenge_activity_types t where t.challenge_id=c.id)
    and (not p_shared_only or exists(select 1 from public.activity_sharing s where s.activity_id=a.id and s.owner_id=p_user and s.visibility='FRIENDS'))
  left join lateral (
    select max(case when segments=5 and span=4 and meters=5000 and seconds>0 then 5000/seconds end)::numeric speed
    from (select count(*) over w segments,max(split_number) over w-min(split_number) over w span,
      sum(distance_meters) over w meters,sum(duration_seconds) over w seconds
      from public.activity_splits where activity_id=a.id
      window w as (order by split_number rows between 4 preceding and current row)) windows
    where c.metric_type='FASTEST_5K'
  ) five on true
  where c.id=p_challenge and (c.scope<>'GROUP' or exists(select 1 from public.group_members gm where gm.group_id=c.group_id and gm.user_id=p_user))
  group by c.metric_type;
$$;

create function public._social_refresh_completions(p_actor uuid) returns void language plpgsql security definer
set search_path=public,pg_temp as $$ declare c record; qualifies boolean; previous boolean; opted_in boolean; consent_started timestamptz;
begin
  perform pg_advisory_xact_lock(hashtextextended(p_actor::text,917));
  select coalesce(s.auto_share_new_activities,false),s.share_after into opted_in,consent_started from public.social_privacy_settings s where s.user_id=p_actor;
  for c in select ch.* from public.challenges ch join public.challenge_participants cp on cp.challenge_id=ch.id and cp.user_id=p_actor
    where ch.status not in ('ARCHIVED','CANCELLED') and ch.start_at<=now()
  loop
    qualifies := coalesce(public._social_challenge_value(c.id,p_actor,false),0)>=c.target_value;
    select reached into previous from public.social_challenge_completions where challenge_id=c.id and user_id=p_actor;
    insert into public.social_challenge_completions(challenge_id,user_id,reached,first_reached_at)
      values(c.id,p_actor,qualifies,case when qualifies then now() end)
    on conflict(challenge_id,user_id) do update set reached=excluded.reached,
      first_reached_at=coalesce(social_challenge_completions.first_reached_at,excluded.first_reached_at),updated_at=now();
    -- Only a transition caused by a write can publish. Leaderboard reads never write.
    -- Re-crossing the goal reuses the original event ID and original publication time.
    if qualifies and not coalesce(previous,false) and coalesce(opted_in,false) and c.scope in ('FRIENDS','GROUP') then
      insert into public.social_events(actor_id,event_type,challenge_id,occurred_at,consent_since)
      values(p_actor,'CHALLENGE_COMPLETED',c.id,date_trunc('hour',now()),consent_started)
      on conflict(actor_id,challenge_id,event_type) where challenge_id is not null do update set consent_since=excluded.consent_since;
    end if;
  end loop;
end $$;

create function public._social_refresh_activity(p_id uuid) returns void language plpgsql security definer
set search_path=public,pg_temp as $$ declare a public.activities%rowtype;
begin
  select * into a from public.activities where id=p_id;
  if not found then return; end if;
  perform pg_advisory_xact_lock(hashtextextended(a.user_id::text,917));
  -- Legacy direct REST inserts default to private, even if auto-sharing is on.
  -- New clients use sync_social_activity to select visibility atomically.
  insert into public.activity_sharing(activity_id,owner_id) values(a.id,a.user_id) on conflict do nothing;
  if a.ended_at is not null and a.ended_at<=now() then
    insert into public.social_events(actor_id,event_type,activity_id,occurred_at)
      values(a.user_id,'ACTIVITY_COMPLETED',a.id,date_trunc('hour',a.ended_at))
      on conflict(actor_id,activity_id,event_type) where activity_id is not null do update set occurred_at=excluded.occurred_at;
    if public._social_is_record(a.id) then
      insert into public.social_events(actor_id,event_type,activity_id,occurred_at)
        values(a.user_id,'PERSONAL_RECORD',a.id,date_trunc('hour',a.ended_at))
        on conflict(actor_id,activity_id,event_type) where activity_id is not null do nothing;
    end if;
  end if;
end $$;

create function public._social_activity_changed() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$ begin
  if TG_OP='DELETE' then perform public._social_refresh_completions(old.user_id); return old; end if;
  if TG_OP='UPDATE' and old.user_id<>new.user_id then raise exception 'Activity owner is immutable' using errcode='42501'; end if;
  perform public._social_refresh_activity(new.id);
  perform public._social_refresh_completions(new.user_id);
  return new;
end $$;
create trigger social_activity_changed after insert or update of user_id,activity_type,started_at,ended_at,
  duration_seconds,distance_meters,elevation_gain_meters or delete on public.activities
  for each row execute function public._social_activity_changed();
create function public._social_split_changed() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$ declare owner uuid; aid uuid;
begin
  aid := case when TG_OP='DELETE' then old.activity_id else new.activity_id end;
  select user_id into owner from public.activities where id=aid;
  if owner is not null then perform public._social_refresh_completions(owner); end if;
  return null;
end $$;
create trigger social_split_changed after insert or update or delete on public.activity_splits
  for each row execute function public._social_split_changed();
create function public._social_challenge_changed() returns trigger language plpgsql security definer
set search_path=public,pg_temp as $$ declare person record; cid uuid;
begin
  if TG_TABLE_NAME='challenges' then cid:=new.id; else cid:=case when TG_OP='DELETE' then old.challenge_id else new.challenge_id end; end if;
  for person in select user_id from public.challenge_participants where challenge_id=cid order by user_id loop
    perform public._social_refresh_completions(person.user_id);
  end loop;
  return null;
end $$;
create trigger social_challenge_changed after update of target_value,start_at,end_at,status,closed_at,scope,group_id,metric_type
  on public.challenges for each row execute function public._social_challenge_changed();
create trigger social_participant_changed after insert or delete on public.challenge_participants
  for each row execute function public._social_challenge_changed();
create trigger social_challenge_types_changed after insert or update or delete on public.challenge_activity_types
  for each row execute function public._social_challenge_changed();

create function public.get_social_privacy() returns jsonb language plpgsql stable security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb;
begin
  select jsonb_build_object('auto_share_new_activities',auto_share_new_activities,'show_profile_statistics',show_profile_statistics,'revision',revision)
    into result from public.social_privacy_settings where user_id=actor;
  return coalesce(result,'{"auto_share_new_activities":false,"show_profile_statistics":false,"revision":0}'::jsonb);
end $$;
create function public.set_social_privacy(p_auto_share boolean,p_show_statistics boolean,p_expected_revision bigint)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); s public.social_privacy_settings%rowtype;
begin
  if p_auto_share is null or p_show_statistics is null then raise exception 'Invalid privacy settings' using errcode='22023'; end if;
  perform pg_advisory_xact_lock(hashtextextended(actor::text,917));
  insert into public.social_privacy_settings(user_id) values(actor) on conflict do nothing;
  select * into s from public.social_privacy_settings where user_id=actor for update;
  if p_expected_revision is distinct from s.revision then raise exception 'Privacy settings changed; reload' using errcode='40001'; end if;
  update public.social_privacy_settings set auto_share_new_activities=p_auto_share,show_profile_statistics=p_show_statistics,
    share_after=case when p_auto_share and not s.auto_share_new_activities then now() else s.share_after end,
    revision=revision+1,updated_at=now() where user_id=actor;
  return public.get_social_privacy();
end $$;

create function public.get_activity_sharing(p_activity_id uuid) returns jsonb language plpgsql stable security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb;
begin
  if not exists(select 1 from public.activities where id=p_activity_id and user_id=actor) then raise exception 'Activity unavailable' using errcode='42501'; end if;
  select jsonb_build_object('visibility',visibility,'share_dog_name',share_dog_name,'revision',revision) into result
    from public.activity_sharing where activity_id=p_activity_id and owner_id=actor;
  return coalesce(result,'{"visibility":"PRIVATE","share_dog_name":false,"revision":0}'::jsonb);
end $$;
create function public.set_activity_sharing(p_activity_id uuid,p_visibility text,p_share_dog_name boolean,p_expected_revision bigint)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare actor uuid:=public._social_require_actor(); s public.activity_sharing%rowtype;
begin
  if p_visibility is null or p_visibility not in ('PRIVATE','FRIENDS') or p_share_dog_name is null then raise exception 'Invalid sharing' using errcode='22023'; end if;
  perform 1 from public.activities where id=p_activity_id and user_id=actor for update;
  if not found then raise exception 'Activity unavailable' using errcode='42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(actor::text,917));
  insert into public.activity_sharing(activity_id,owner_id) values(p_activity_id,actor) on conflict do nothing;
  select * into s from public.activity_sharing where activity_id=p_activity_id and owner_id=actor for update;
  if p_expected_revision is distinct from s.revision then raise exception 'Sharing changed; reload' using errcode='40001'; end if;
  update public.activity_sharing set visibility=p_visibility,share_dog_name=p_share_dog_name,revision=revision+1,updated_at=now() where activity_id=p_activity_id;
  perform public._social_refresh_activity(p_activity_id);
  return public.get_activity_sharing(p_activity_id);
end $$;

-- Explicit summary allowlist. Android still syncs points/splits with their existing owner-only REST contracts.
-- Missing visibility means preserve an existing choice; for a new row use the creation-time server default.
create function public.sync_social_activity(p_activity jsonb,p_visibility text default null,p_share_dog_name boolean default false)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare actor uuid:=public._social_require_actor(); aid uuid; previous_owner uuid; existed boolean;
  kind text; began timestamptz; ended timestamptz; meters double precision; seconds integer; climb double precision;
  dog uuid; chosen text; s public.social_privacy_settings%rowtype;
begin
  if jsonb_typeof(p_activity)<>'object' or p_activity is null or p_share_dog_name is null
    or (p_visibility is not null and p_visibility not in ('PRIVATE','FRIENDS'))
    or exists(select 1 from jsonb_object_keys(p_activity) k where k not in
      ('id','user_id','activity_type','started_at','ended_at','duration_seconds','distance_meters','avg_pace_seconds_per_km',
       'avg_speed_kmh','elevation_gain_meters','elevation_loss_meters','steps','dog_id','note')) then
    raise exception 'Invalid activity fields' using errcode='22023'; end if;
  if (p_activity->>'user_id')::uuid is distinct from actor then raise exception 'Wrong owner' using errcode='42501'; end if;
  aid:=(p_activity->>'id')::uuid; kind:=public._social_kind(p_activity->>'activity_type');
  began:=(p_activity->>'started_at')::timestamptz; ended:=(p_activity->>'ended_at')::timestamptz;
  meters:=(p_activity->>'distance_meters')::double precision; seconds:=(p_activity->>'duration_seconds')::integer;
  climb:=(p_activity->>'elevation_gain_meters')::double precision; dog:=(p_activity->>'dog_id')::uuid;
  if aid is null or kind is null or kind not in ('RUN','WALK','DOG_RUN','DOG_WALK') or began is null or ended is null
    or ended<began or ended>now() or meters is null or not (meters between 0 and 10000000)
    or seconds is null or seconds not between 0 and 31536000 or (climb is not null and not (climb between 0 and 100000))
    or char_length(coalesce(p_activity->>'note',''))>500 or (kind in ('DOG_RUN','DOG_WALK'))<>(dog is not null)
    or (dog is not null and not exists(select 1 from public.dogs where id=dog and owner_id=actor)) then
    raise exception 'Invalid activity values' using errcode='22023'; end if;
  select user_id into previous_owner from public.activities where id=aid for update;
  existed:=found;
  perform pg_advisory_xact_lock(hashtextextended(actor::text,917));
  if existed and previous_owner<>actor then raise exception 'Wrong owner' using errcode='42501'; end if;
  insert into public.activities(id,user_id,activity_type,started_at,ended_at,duration_seconds,distance_meters,
    avg_pace_seconds_per_km,avg_speed_kmh,elevation_gain_meters,elevation_loss_meters,steps,dog_id,note)
  values(aid,actor,kind,began,ended,seconds,meters,case when meters>=100 and seconds>0 then seconds/(meters/1000) end,
    case when seconds>0 then meters/seconds*3.6 end,climb,(p_activity->>'elevation_loss_meters')::double precision,
    (p_activity->>'steps')::integer,dog,nullif(btrim(p_activity->>'note'),''))
  on conflict(id) do update set activity_type=excluded.activity_type,started_at=excluded.started_at,ended_at=excluded.ended_at,
    duration_seconds=excluded.duration_seconds,distance_meters=excluded.distance_meters,avg_pace_seconds_per_km=excluded.avg_pace_seconds_per_km,
    avg_speed_kmh=excluded.avg_speed_kmh,elevation_gain_meters=excluded.elevation_gain_meters,elevation_loss_meters=excluded.elevation_loss_meters,
    steps=excluded.steps,dog_id=excluded.dog_id,note=excluded.note,updated_at=now() where activities.user_id=actor;
  if not found then raise exception 'Wrong owner' using errcode='42501'; end if;
  if not existed then
    select * into s from public.social_privacy_settings where user_id=actor;
    chosen:=coalesce(p_visibility,case when s.auto_share_new_activities and began>=s.share_after then 'FRIENDS' else 'PRIVATE' end);
    update public.activity_sharing set visibility=chosen,share_dog_name=p_share_dog_name where activity_id=aid and owner_id=actor;
  end if;
  -- Retried uploads never override an existing privacy choice; use set_activity_sharing with revision for edits.
  return jsonb_build_object('id',aid,'sharing',public.get_activity_sharing(aid));
end $$;

create function public._social_event_visible(p_event uuid,p_viewer uuid) returns boolean language sql stable security definer
set search_path=public,pg_temp as $$
  select exists(select 1 from public.social_events e join public.profiles p on p.id=e.actor_id
    where e.id=p_event and p_viewer is not null and public._social_friends(e.actor_id,p_viewer)
      and not p.banned and p.archived_at is null and (
        (e.activity_id is not null and exists(select 1 from public.activities a join public.activity_sharing s on s.activity_id=a.id
          where a.id=e.activity_id and a.user_id=e.actor_id and s.owner_id=e.actor_id and s.visibility='FRIENDS'
            and a.ended_at<=now() and a.ended_at is not null and (e.event_type<>'PERSONAL_RECORD' or public._social_is_record(a.id))))
        or (e.event_type='CHALLENGE_COMPLETED' and exists(select 1 from public.social_challenge_completions cc
          join public.challenges c on c.id=cc.challenge_id join public.social_privacy_settings pref on pref.user_id=cc.user_id
          where cc.challenge_id=e.challenge_id and cc.user_id=e.actor_id and cc.reached
            and c.status not in ('ARCHIVED','CANCELLED') and c.scope in ('FRIENDS','GROUP') and pref.auto_share_new_activities
            and e.consent_since=pref.share_after
            and exists(select 1 from public.challenge_participants cp where cp.challenge_id=c.id and cp.user_id=p_viewer)
            and public._challenge_can_view(c.id,p_viewer)
            and (c.scope<>'GROUP' or exists(select 1 from public.group_members gm where gm.group_id=c.group_id and gm.user_id=e.actor_id))))
      ));
$$;

create function public.get_social_feed(p_before_at timestamptz default null,p_before_id uuid default null,p_limit integer default 20)
returns jsonb language plpgsql stable security definer set search_path=public,pg_temp as $$
declare actor uuid:=public._social_require_actor(); result jsonb;
begin
  if p_limit is null or p_limit not between 1 and 50 or (p_before_at is null)<>(p_before_id is null) then raise exception 'Invalid pagination' using errcode='22023'; end if;
  with friends as (
    select case when user_low_id=actor then user_high_id else user_low_id end id from public.friendships where actor in (user_low_id,user_high_id)
  ), candidates as materialized (
    select e.* from public.social_events e join friends f on f.id=e.actor_id
    where (p_before_at is null or (e.occurred_at,e.id)<(p_before_at,p_before_id))
      and public._social_event_visible(e.id,actor) order by e.occurred_at desc,e.id desc limit p_limit+1
  ), page as (select * from candidates order by occurred_at desc,id desc limit p_limit), enriched as (
    select e.occurred_at,e.id,jsonb_build_object('id',e.id,'event_type',e.event_type,'actor_id',e.actor_id,
      'display_name',p.display_name,'avatar_path',p.avatar_path,'occurred_at',e.occurred_at,
      'activity_type',public._social_kind(a.activity_type),
      'dog_name',case when s.share_dog_name and d.owner_id=e.actor_id then d.name end,
      'distance_meters',case when e.activity_id is not null then a.distance_meters end,
      'duration_seconds',case when e.activity_id is not null then a.duration_seconds end,
      'pace_seconds_per_km',case when a.distance_meters>=100 and a.duration_seconds>0 then a.duration_seconds/(a.distance_meters/1000) end,
      'record_type',case when e.event_type='PERSONAL_RECORD' then 'LONGEST_RUN' end,
      'challenge_id',e.challenge_id,'challenge_title',c.title,
      'cheer_count',(select count(*) from public.social_cheers sc where sc.event_id=e.id),
      'cheered_by_me',exists(select 1 from public.social_cheers sc where sc.event_id=e.id and sc.user_id=actor)) item
    from page e join public.profiles p on p.id=e.actor_id left join public.activities a on a.id=e.activity_id
    left join public.activity_sharing s on s.activity_id=a.id left join public.dogs d on d.id=a.dog_id
    left join public.challenges c on c.id=e.challenge_id
  ) select jsonb_build_object('items',coalesce((select jsonb_agg(item order by occurred_at desc,id desc) from enriched),'[]'::jsonb),
    'next_cursor',case when (select count(*) from candidates)>p_limit then
      (select jsonb_build_object('occurred_at',occurred_at,'id',id) from page order by occurred_at,id limit 1) end) into result;
  return result;
end $$;
create function public.set_social_cheer(p_event_id uuid,p_cheered boolean) returns jsonb language plpgsql security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor();
begin
  if p_cheered is null or not public._social_event_visible(p_event_id,actor) then raise exception 'Event unavailable' using errcode='42501'; end if;
  if p_cheered then insert into public.social_cheers(event_id,user_id) values(p_event_id,actor) on conflict do nothing;
  else delete from public.social_cheers where event_id=p_event_id and user_id=actor; end if;
  return jsonb_build_object('cheer_count',(select count(*) from public.social_cheers where event_id=p_event_id),'cheered_by_me',p_cheered);
end $$;

-- Basic private profile metadata is visible to its owner and accepted friends. Statistics below
-- require a PUBLIC profile plus explicit statistics opt-in, even for accepted friends.
create function public._social_can_read_profile(p_profile uuid) returns boolean language sql stable security definer
set search_path=public,pg_temp as $$
  select public._account_active() and exists(select 1 from public.profiles p where p.id=p_profile and not p.banned and p.archived_at is null
    and (p.id=auth.uid() or p.is_public or public._social_friends(p.id,auth.uid())));
$$;
create policy social_profile_visibility on public.profiles as restrictive for select to authenticated
using(public._social_can_read_profile(id));

create function public.get_public_profile_statistics(p_profile_id uuid) returns jsonb language plpgsql stable security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb;
begin
  if not exists(select 1 from public.profiles p join public.social_privacy_settings s on s.user_id=p.id
    where p.id=p_profile_id and p.is_public and not p.banned and p.archived_at is null and s.show_profile_statistics) then
    return '{"status":"PRIVATE"}'::jsonb;
  end if;
  with eligible as materialized (
    select a.*,public._social_kind(a.activity_type) kind from public.activities a join public.activity_sharing s on s.activity_id=a.id
    where a.user_id=p_profile_id and s.owner_id=p_profile_id and s.visibility='FRIENDS' and a.ended_at<=now()
      and a.distance_meters>=0 and a.duration_seconds>=0
  ), weeks as (
    select day::date week_start,coalesce(sum(a.distance_meters),0) distance_meters,count(a.id) activity_count
    from generate_series(date_trunc('week',now() at time zone 'Europe/Stockholm')-interval '7 weeks',
      date_trunc('week',now() at time zone 'Europe/Stockholm'),'1 week') day
    left join eligible a on date_trunc('week',a.started_at at time zone 'Europe/Stockholm')=day group by day
  ) select jsonb_build_object('status',case when count(*)=0 then 'EMPTY' else 'VISIBLE' end,
    'activity_count',count(*),'distance_meters',coalesce(sum(distance_meters),0),'duration_seconds',coalesce(sum(duration_seconds),0),
    'run_count',count(*) filter(where kind in ('RUN','DOG_RUN')),'walk_count',count(*) filter(where kind in ('WALK','DOG_WALK')),
    'distance_last_7_days',coalesce(sum(distance_meters) filter(where started_at>=now()-interval '7 days'),0),
    'distance_last_30_days',coalesce(sum(distance_meters) filter(where started_at>=now()-interval '30 days'),0),
    'run_pace_seconds_per_km',sum(duration_seconds) filter(where kind in ('RUN','DOG_RUN') and distance_meters>=100 and duration_seconds>0)
      /nullif(sum(distance_meters) filter(where kind in ('RUN','DOG_RUN') and distance_meters>=100 and duration_seconds>0)/1000,0),
    'longest_run_meters',coalesce(max(distance_meters) filter(where kind in ('RUN','DOG_RUN')),0),
    'completed_challenge_count',(select count(*) from public.challenges c join public.challenge_participants cp on cp.challenge_id=c.id
      where cp.user_id=p_profile_id and c.scope<>'PRIVATE' and c.status not in ('CANCELLED','ARCHIVED')
        and coalesce(public._social_challenge_value(c.id,p_profile_id,true),0)>=c.target_value),
    'weeks',(select jsonb_agg(to_jsonb(w) order by w.week_start) from weeks w)) into result from eligible;
  return result;
end $$;

-- Every new function is denied by default, including all SECURITY DEFINER helpers/triggers.
do $$ declare f record; begin
  for f in select p.oid::regprocedure signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and (p.proname like '\_social\_%' escape '\' or p.proname in
      ('get_social_privacy','set_social_privacy','get_activity_sharing','set_activity_sharing','sync_social_activity',
       'get_social_feed','set_social_cheer','get_public_profile_statistics'))
  loop execute format('revoke all on function %s from public,anon,authenticated',f.signature); end loop;
end $$;
grant execute on function public.get_social_privacy(),public.set_social_privacy(boolean,boolean,bigint),
  public.get_activity_sharing(uuid),public.set_activity_sharing(uuid,text,boolean,bigint),public.sync_social_activity(jsonb,text,boolean),
  public.get_social_feed(timestamptz,uuid,integer),public.set_social_cheer(uuid,boolean),
  public.get_public_profile_statistics(uuid),public._social_can_read_profile(uuid) to authenticated;
notify pgrst,'reload schema';
commit;

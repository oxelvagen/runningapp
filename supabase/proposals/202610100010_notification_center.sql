-- Historical review copy. Approved and applied 2026-10-10; canonical SQL is in supabase/migrations/202610100010_notification_center.sql.
-- Derived inbox: no client-created events, no fanout copies of private content.
begin;

create table public.notification_preferences (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  categories jsonb not null default '{"activities":true,"records":true,"new_challenges":true,"completed_challenges":true,"cheers":true,"friends":true,"groups":true}'::jsonb,
  revision bigint not null default 0,
  updated_at timestamptz not null default now()
);
create table public.notification_receipts (
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  notification_id uuid not null,
  read_at timestamptz not null default now(),
  primary key(recipient_id,notification_id)
);
alter table public.notification_preferences enable row level security;
alter table public.notification_receipts enable row level security;
revoke all on public.notification_preferences,public.notification_receipts from public,anon,authenticated;

create function public._notification_id(p_source text,p_recipient uuid) returns uuid
language sql immutable set search_path=public,pg_temp as $$ select md5('mintracker-notification-v1:'||p_source||':'||p_recipient::text)::uuid; $$;

create function public._notification_actor_visible(p_actor uuid) returns boolean
language sql stable security definer set search_path=public,pg_temp as $$
  select exists(select 1 from public.profiles p where p.id=p_actor and not p.banned and p.archived_at is null
    and (p.account_type<>'NPC' or (
      exists(select 1 from public.npc_profiles n where n.profile_id=p.id and n.notifications_enabled and n.enabled and n.archived_at is null)
      and exists(select 1 from public.app_settings where npc_notifications_enabled))));
$$;

-- Also protect older clients reading the existing table directly.
create function public._notification_legacy_visible(p_id uuid,p_viewer uuid) returns boolean
language sql stable security definer set search_path=public,pg_temp as $$
  select exists(select 1 from public.notifications n where n.id=p_id and n.user_id=p_viewer
    and (n.actor_profile_id is null or public._notification_actor_visible(n.actor_profile_id))
    and (n.activity_id is null or exists(select 1 from public.activities a where a.id=n.activity_id and
      (a.user_id=p_viewer or (public._social_friends(a.user_id,p_viewer) and exists(select 1 from public.activity_sharing s
        where s.activity_id=a.id and s.owner_id=a.user_id and s.visibility='FRIENDS')))))
    and (n.challenge_id is null or exists(select 1 from public.challenges c where c.id=n.challenge_id
      and c.status not in ('CANCELLED','ARCHIVED') and public._challenge_can_view(c.id,p_viewer)
      and (c.creator_id=p_viewer or c.scope='GROUP' or c.challenge_origin<>'USER' or public._social_friends(c.creator_id,p_viewer)))));
$$;
create policy notification_current_visibility on public.notifications as restrictive for select to authenticated
  using(public._notification_legacy_visible(id,auth.uid()));
-- This old trigger generated friend notifications even for PRIVATE activities.
-- Shared activity and record notifications now derive exclusively from social_events.
drop trigger mintracker_activity_event on public.activities;

create function public._notification_candidates(p_viewer uuid)
returns table(id uuid,recipient_id uuid,actor_id uuid,event_type text,target_type text,target_id uuid,
  created_at timestamptz,category text,payload jsonb)
language sql stable security definer set search_path=public,pg_temp as $$
  with raw as (
    select 'social:'||e.id source,e.actor_id,e.event_type,
      case when e.activity_id is not null then 'ACTIVITY' else 'CHALLENGE' end target_type,
      coalesce(e.activity_id,e.challenge_id) target_id,e.occurred_at created_at,
      case e.event_type when 'ACTIVITY_COMPLETED' then 'activities' when 'PERSONAL_RECORD' then 'records' else 'completed_challenges' end category,
      jsonb_build_object('activity_type',public._social_kind(a.activity_type),'distance_meters',a.distance_meters,
        'dog_name',case when s.share_dog_name and d.owner_id=e.actor_id then d.name end,
        'challenge_title',c.title,'social_event_id',e.id) payload
    from public.social_events e left join public.activities a on a.id=e.activity_id
      left join public.activity_sharing s on s.activity_id=a.id left join public.dogs d on d.id=a.dog_id
      left join public.challenges c on c.id=e.challenge_id
    where e.actor_id in (select case when f.user_low_id=p_viewer then f.user_high_id else f.user_low_id end
      from public.friendships f where p_viewer in (f.user_low_id,f.user_high_id))
      and public._social_event_visible(e.id,p_viewer)
    union all
    select 'challenge:'||c.id,c.creator_id,'CHALLENGE_CREATED','CHALLENGE',c.id,c.created_at,'new_challenges',
      jsonb_build_object('challenge_title',c.title)
    from public.challenges c where c.creator_id<>p_viewer and c.scope in ('FRIENDS','GROUP')
      and c.status not in ('CANCELLED','ARCHIVED') and public._challenge_can_view(c.id,p_viewer)
      and (public._social_friends(c.creator_id,p_viewer) or (c.scope='GROUP' and exists(
        select 1 from public.group_members gm where gm.group_id=c.group_id and gm.user_id=c.creator_id)))
    union all
    select 'cheer:'||sc.event_id||':'||sc.user_id,sc.user_id,'CHEER',
      case when e.activity_id is null then 'CHALLENGE' else 'ACTIVITY' end,
      coalesce(e.activity_id,e.challenge_id),sc.created_at,'cheers',
      jsonb_build_object('activity_type',public._social_kind(a.activity_type),'challenge_title',c.title,'social_event_id',e.id)
    from public.social_cheers sc join public.social_events e on e.id=sc.event_id
      left join public.activities a on a.id=e.activity_id left join public.challenges c on c.id=e.challenge_id
    where e.actor_id=p_viewer and sc.user_id<>p_viewer and public._social_event_visible(e.id,sc.user_id)
    union all
    select 'request:'||f.id,f.sender_id,'FRIEND_REQUEST','FRIEND_REQUEST',f.id,f.created_at,'friends','{}'::jsonb
    from public.friend_requests f where f.receiver_id=p_viewer and f.status='PENDING' and f.expires_at>now()
    union all
    select 'accepted:'||f.id,f.receiver_id,'FRIEND_ACCEPTED','PROFILE',f.receiver_id,coalesce(f.responded_at,f.created_at),'friends','{}'::jsonb
    from public.friend_requests f where f.sender_id=p_viewer and f.status='ACCEPTED' and public._social_friends(f.sender_id,f.receiver_id)
    union all
    select 'membership:'||gm.group_id||':'||gm.user_id||':'||gm.joined_at,null::uuid,'GROUP_JOINED','GROUP',g.id,gm.joined_at,'groups',
      jsonb_build_object('group_name',g.name)
    from public.group_members gm join public.groups g on g.id=gm.group_id
    where gm.user_id=p_viewer and g.owner_id<>p_viewer
    union all
    -- Only the current owner gets individual new-member notices, not every member.
    select 'member:'||gm.group_id||':'||gm.user_id||':'||gm.joined_at,gm.user_id,'GROUP_MEMBER_JOINED','GROUP',g.id,gm.joined_at,'groups',
      jsonb_build_object('group_name',g.name)
    from public.group_members gm join public.groups g on g.id=gm.group_id
    where g.owner_id=p_viewer and gm.user_id<>p_viewer
      and exists(select 1 from public.group_members own where own.group_id=g.id and own.user_id=p_viewer)
    union all
    select 'legacy:'||n.id,n.actor_profile_id,n.type,
      case when n.activity_id is not null then 'ACTIVITY' when n.challenge_id is not null then 'CHALLENGE' else 'SYSTEM' end,
      coalesce(n.activity_id,n.challenge_id,n.id),n.created_at,
      case when n.type='SYSTEM_MESSAGE' then 'system' when n.type='CHALLENGE_INVITE' then 'new_challenges' else 'completed_challenges' end,
      jsonb_build_object('title',n.title,'body',n.body,'legacy_id',n.id,'legacy_read',n.is_read)
    from public.notifications n where n.user_id=p_viewer and n.type not in ('FRIEND_ACTIVITY','NPC_ACTIVITY')
      and public._notification_legacy_visible(n.id,p_viewer)
      and not (n.type='CHALLENGE_COMPLETED' and exists(select 1 from public.social_events e
        where e.challenge_id=n.challenge_id and e.actor_id=n.actor_profile_id and e.event_type='CHALLENGE_COMPLETED'
          and public._social_event_visible(e.id,p_viewer)))
  )
  select public._notification_id(r.source,p_viewer),p_viewer,r.actor_id,r.event_type,r.target_type,r.target_id,
    r.created_at,r.category,r.payload||jsonb_build_object('display_name',p.display_name,'avatar_path',p.avatar_path,
      'is_npc',coalesce(p.account_type='NPC',false))
  from raw r left join public.profiles p on p.id=r.actor_id
    left join public.notification_preferences pref on pref.user_id=p_viewer
  where (r.actor_id is null or ((r.actor_id<>p_viewer or r.category='system') and public._notification_actor_visible(r.actor_id)))
    and (r.category='system' or (coalesce((pref.categories->>r.category)::boolean,true)
      and exists(select 1 from public.feature_flags where key='feature_social_notifications' and enabled)));
$$;

create function public.get_notification_preferences() returns jsonb language plpgsql security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb; begin
  insert into public.notification_preferences(user_id) values(actor) on conflict do nothing;
  select jsonb_build_object('categories',categories,'revision',revision) into result from public.notification_preferences where user_id=actor;
  return result;
end $$;
create function public.set_notification_preferences(p_categories jsonb,p_expected_revision bigint) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); begin
  if p_categories is null or jsonb_typeof(p_categories)<>'object' or
    (select count(*) from jsonb_object_keys(p_categories))<>7 or exists(select 1 from jsonb_each(p_categories) e
      where e.key not in ('activities','records','new_challenges','completed_challenges','cheers','friends','groups')
        or jsonb_typeof(e.value)<>'boolean') then raise exception 'Invalid categories' using errcode='22023'; end if;
  perform public.get_notification_preferences();
  update public.notification_preferences set categories=p_categories,revision=revision+1,updated_at=now()
    where user_id=actor and revision=p_expected_revision;
  if not found then raise exception 'Preferences changed; reload' using errcode='40001'; end if;
  return public.get_notification_preferences();
end $$;

create function public._notification_unread(p_viewer uuid) returns bigint language sql stable security definer
set search_path=public,pg_temp as $$
  select count(*) from public._notification_candidates(p_viewer) n left join public.notification_receipts r
    on r.recipient_id=p_viewer and r.notification_id=n.id
  where r.read_at is null and not coalesce((n.payload->>'legacy_read')::boolean,false);
$$;
create function public.get_notification_unread_count() returns bigint language plpgsql stable security definer
set search_path=public,pg_temp as $$ begin return public._notification_unread(public._social_require_actor()); end $$;

create function public.get_notification_center(p_unread_only boolean default false,p_before_at timestamptz default null,
  p_before_id uuid default null,p_limit integer default 20) returns jsonb language plpgsql stable security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb; begin
  if p_limit is null or p_limit not between 1 and 50 or p_unread_only is null or
    (p_before_at is null)<>(p_before_id is null) then raise exception 'Invalid pagination' using errcode='22023'; end if;
  with candidates as materialized (
    select n.*,coalesce(r.read_at,case when coalesce((n.payload->>'legacy_read')::boolean,false) then n.created_at end) read_at
    from public._notification_candidates(actor) n left join public.notification_receipts r
      on r.recipient_id=actor and r.notification_id=n.id
    where (p_before_at is null or (n.created_at,n.id)<(p_before_at,p_before_id))
      and (not p_unread_only or (r.read_at is null and not coalesce((n.payload->>'legacy_read')::boolean,false)))
    order by n.created_at desc,n.id desc limit p_limit+1
  ), page as (select * from candidates order by created_at desc,id desc limit p_limit)
  select jsonb_build_object('items',coalesce((select jsonb_agg(to_jsonb(p)-'category' order by created_at desc,id desc) from page p),'[]'::jsonb),
    'unread_count',public._notification_unread(actor),
    'next_cursor',case when (select count(*) from candidates)>p_limit then
      (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at,id limit 1) end) into result;
  return result;
end $$;

create function public.read_notification(p_id uuid) returns jsonb language plpgsql security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); n record; begin
  select * into n from public._notification_candidates(actor) where id=p_id;
  if not found then return jsonb_build_object('available',false,'unread_count',public._notification_unread(actor)); end if;
  insert into public.notification_receipts(recipient_id,notification_id) values(actor,p_id) on conflict do nothing;
  if n.payload ? 'legacy_id' then update public.notifications set is_read=true where id=(n.payload->>'legacy_id')::uuid and user_id=actor; end if;
  return jsonb_build_object('available',true,'unread_count',public._notification_unread(actor),
    'target_type',n.target_type,'target_id',n.target_id,'actor_id',n.actor_id);
end $$;
create function public.read_all_notifications() returns bigint language plpgsql security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); begin
  insert into public.notification_receipts(recipient_id,notification_id)
    select actor,id from public._notification_candidates(actor) on conflict do nothing;
  update public.notifications set is_read=true where user_id=actor and id in
    (select (payload->>'legacy_id')::uuid from public._notification_candidates(actor) where payload ? 'legacy_id');
  return public._notification_unread(actor);
end $$;

-- Never return GPS, notes, exact activity times or arbitrary activity rows to a visitor.
create function public.get_notification_activity(p_activity_id uuid) returns jsonb language plpgsql stable security definer
set search_path=public,pg_temp as $$ declare actor uuid:=public._social_require_actor(); result jsonb; begin
  select jsonb_build_object('id',a.id,'actor_id',a.user_id,'display_name',p.display_name,'avatar_path',p.avatar_path,
    'is_npc',p.account_type='NPC','activity_type',public._social_kind(a.activity_type),
    'distance_meters',a.distance_meters,'duration_seconds',a.duration_seconds,
    'dog_name',case when s.share_dog_name and d.owner_id=a.user_id then d.name end)
  into result from public.activities a join public.profiles p on p.id=a.user_id
    left join public.activity_sharing s on s.activity_id=a.id left join public.dogs d on d.id=a.dog_id
  where a.id=p_activity_id and a.ended_at<=now() and public._notification_actor_visible(a.user_id)
    and (a.user_id=actor or (s.owner_id=a.user_id and s.visibility='FRIENDS' and public._social_friends(a.user_id,actor)));
  if result is null then raise exception 'Activity unavailable' using errcode='42501'; end if;
  return result;
end $$;

revoke all on function public._notification_id(text,uuid),public._notification_actor_visible(uuid),
  public._notification_legacy_visible(uuid,uuid),public._notification_candidates(uuid),public._notification_unread(uuid),
  public.get_notification_preferences(),public.set_notification_preferences(jsonb,bigint),public.get_notification_unread_count(),
  public.get_notification_center(boolean,timestamptz,uuid,integer),public.read_notification(uuid),
  public.read_all_notifications(),public.get_notification_activity(uuid) from public,anon,authenticated;
-- The RLS helper still enforces recipient=auth.uid(), independently of supplied arguments.
-- This wrapper avoids exposing the internal security-definer helper as a client RPC.
create function public.notification_row_visible(p_id uuid) returns boolean language sql stable security definer
set search_path=public,pg_temp as $$ select public._notification_legacy_visible(p_id,auth.uid()); $$;
revoke all on function public.notification_row_visible(uuid) from public,anon,authenticated;
grant execute on function public.notification_row_visible(uuid) to authenticated;
drop policy notification_current_visibility on public.notifications;
create policy notification_current_visibility on public.notifications as restrictive for select to authenticated
  using(public.notification_row_visible(id));
grant execute on function public.get_notification_preferences(),public.set_notification_preferences(jsonb,bigint),
  public.get_notification_unread_count(),public.get_notification_center(boolean,timestamptz,uuid,integer),
  public.read_notification(uuid),public.read_all_notifications(),public.get_notification_activity(uuid) to authenticated;

commit;

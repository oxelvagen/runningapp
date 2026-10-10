-- Minimal contract fixture for schemas whose original migration is not in this repo.
create role anon;
create role authenticated;
create role service_role bypassrls;
create schema auth;
create table auth.users(id uuid primary key default gen_random_uuid(),email text,email_confirmed_at timestamptz,
  created_at timestamptz default now(),last_sign_in_at timestamptz,raw_app_meta_data jsonb default '{}');
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
create function auth.role() returns text language sql stable as $$ select nullif(current_setting('request.jwt.claim.role',true),'') $$;
grant usage on schema public,auth to anon,authenticated,service_role;
grant execute on function auth.uid(),auth.role() to anon,authenticated,service_role;
create table public.profiles(id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null,username text unique,bio text,is_public boolean default false,avatar_path text,
  created_at timestamptz default now(),updated_at timestamptz default now());
create table public.groups(id uuid primary key default gen_random_uuid(),name text,owner_id uuid references auth.users(id) on delete cascade);
create table public.group_members(group_id uuid references public.groups(id) on delete cascade,user_id uuid references auth.users(id) on delete cascade,role text,
  primary key(group_id,user_id));
create table public.friendships(user_low_id uuid references auth.users(id) on delete cascade,user_high_id uuid references auth.users(id) on delete cascade,
  primary key(user_low_id,user_high_id));
-- Existing production social contracts used by notification center 010.
alter table public.friendships add column created_at timestamptz not null default now();
alter table public.group_members add column joined_at timestamptz not null default now();
create table public.friend_requests(id uuid primary key default gen_random_uuid(),sender_id uuid references auth.users(id) on delete cascade,
  receiver_id uuid references auth.users(id) on delete cascade,status text not null default 'PENDING',created_at timestamptz not null default now(),
  responded_at timestamptz,expires_at timestamptz not null default now()+interval '30 days');
create table public.dogs(id uuid primary key default gen_random_uuid(),owner_id uuid references auth.users(id) on delete cascade,name text,
  archived_at timestamptz);
create table public.activities(id uuid primary key default gen_random_uuid(),user_id uuid references auth.users(id) on delete cascade,
  activity_type text not null,started_at timestamptz not null,ended_at timestamptz,duration_seconds integer not null,
  distance_meters double precision not null,avg_pace_seconds_per_km double precision,elevation_gain_meters double precision,dog_id uuid references public.dogs(id) on delete set null);
alter table public.profiles enable row level security;
create table public.activity_splits(activity_id uuid references public.activities(id) on delete cascade,user_id uuid not null references auth.users(id) on delete cascade,split_number integer,
  distance_meters numeric,duration_seconds numeric,pace_seconds_per_km numeric,primary key(activity_id,split_number));
create policy owner_profile on public.profiles for all to authenticated using(id=auth.uid()) with check(id=auth.uid());
-- Match the existing production SELECT policy; proposal 009 adds a restrictive guard.
create policy readable_profiles on public.profiles for select to authenticated using(true);
grant select,insert,update on public.profiles to authenticated;
grant select,insert,update on public.activities to authenticated;
-- Production sync contract: keep the fixture aligned with the audited live schema.
alter table public.activities add column avg_speed_kmh double precision,
  add column elevation_loss_meters double precision,add column steps integer,
  add column created_at timestamptz not null default now(),add column updated_at timestamptz not null default now();
grant delete on public.activities to authenticated;
alter table public.activities enable row level security;
create policy owner_activity on public.activities for all to authenticated using(user_id=auth.uid()) with check(user_id=auth.uid());

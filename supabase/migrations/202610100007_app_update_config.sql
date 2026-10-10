-- PROPOSAL ONLY: requires user approval before installation.
-- Deliberately outside supabase/migrations so db push cannot apply it.
begin;

create table public.app_update_config (
  id boolean primary key default true check (id),
  latest_version_code integer not null check (latest_version_code > 0),
  minimum_version_code integer not null check (minimum_version_code > 0),
  latest_version_name text not null
    check (length(btrim(latest_version_name)) between 1 and 100),
  github_release_tag text not null
    check (length(btrim(github_release_tag)) between 1 and 200),
  apk_asset_name text not null
    check (apk_asset_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]*[.]apk$'
      and length(apk_asset_name) <= 200),
  apk_sha256 text not null check (apk_sha256 ~ '^[0-9a-f]{64}$'),
  release_notes text not null default '' check (length(release_notes) <= 20000),
  policy_revision bigint not null default 1 check (policy_revision > 0),
  updated_at timestamptz not null default clock_timestamp(),
  constraint app_update_versions_consistent
    check (latest_version_code >= minimum_version_code)
);

-- A server-owned revision lets clients reject late/out-of-order responses.
create function public.stamp_app_update_config()
returns trigger language plpgsql set search_path = pg_catalog as $$
begin
  if TG_OP = 'INSERT' then
    new.policy_revision := 1;
    new.updated_at := clock_timestamp();
  else
    new.policy_revision := old.policy_revision + 1;
    new.updated_at := greatest(clock_timestamp(), old.updated_at + interval '1 microsecond');
  end if;
  return new;
end;
$$;

revoke all on function public.stamp_app_update_config()
  from public, anon, authenticated;

create trigger stamp_app_update_config
before insert or update on public.app_update_config
for each row execute function public.stamp_app_update_config();

alter table public.app_update_config enable row level security;
revoke all on table public.app_update_config from public, anon, authenticated;
grant select on table public.app_update_config to anon, authenticated;
create policy app_update_config_public_read
  on public.app_update_config for select
  to anon, authenticated using (true);

-- Publishing is restricted to trusted server/database administration.
-- No mobile client (including an app admin) can publish update policies.
revoke all on table public.app_update_config from service_role;
grant select, insert, update on table public.app_update_config to service_role;

comment on table public.app_update_config is
  'Public update metadata, no secrets. Singleton; publish via UPDATE/upsert, never delete/reinsert.';
comment on column public.app_update_config.apk_sha256 is
  'Lowercase SHA-256 of the exact signed APK uploaded to the configured public GitHub repository.';
comment on column public.app_update_config.policy_revision is
  'Server-owned increasing revision. Clients persist it with the last verified policy.';

-- No initial policy is inserted: the real release tag, APK and checksum must
-- exist before publishing. Missing policy is not evidence of a required update.
notify pgrst, 'reload schema';
commit;

-- Requires explicit approval before execution against Supabase.
-- Repair column shadowing (dogs.name versus storage.objects.name) and avoid
-- ambiguous backslash escaping in the JPEG filename expression. No tables,
-- buckets, ownership rules or public-read permissions are changed.
begin;

alter policy "Avatar dog insert own dog path" on storage.objects to authenticated
with check (
  bucket_id = 'dog-avatars'
  and cardinality(storage.foldername(objects.name)) = 3
  and (storage.foldername(objects.name))[1] = (select auth.uid())::text
  and (storage.foldername(objects.name))[2] = 'dogs'
  and storage.filename(objects.name) ~ '^avatar-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}[.]jpg$'
  and exists (select 1 from public.dogs d
    where d.id::text = (storage.foldername(objects.name))[3]
      and d.owner_id = (select auth.uid()))
);

alter policy "Avatar dog read by owner" on storage.objects to authenticated
using (
  bucket_id = 'dog-avatars'
  and cardinality(storage.foldername(objects.name)) = 3
  and (storage.foldername(objects.name))[1] = (select auth.uid())::text
  and (storage.foldername(objects.name))[2] = 'dogs'
  and exists (select 1 from public.dogs d
    where d.id::text = (storage.foldername(objects.name))[3]
      and d.owner_id = (select auth.uid()))
);

alter policy "Avatar dog update own dog path" on storage.objects to authenticated
using (
  bucket_id = 'dog-avatars'
  and cardinality(storage.foldername(objects.name)) = 3
  and (storage.foldername(objects.name))[1] = (select auth.uid())::text
  and (storage.foldername(objects.name))[2] = 'dogs'
  and exists (select 1 from public.dogs d
    where d.id::text = (storage.foldername(objects.name))[3]
      and d.owner_id = (select auth.uid()))
)
with check (
  bucket_id = 'dog-avatars'
  and cardinality(storage.foldername(objects.name)) = 3
  and (storage.foldername(objects.name))[1] = (select auth.uid())::text
  and (storage.foldername(objects.name))[2] = 'dogs'
  and storage.filename(objects.name) ~ '^avatar-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}[.]jpg$'
  and exists (select 1 from public.dogs d
    where d.id::text = (storage.foldername(objects.name))[3]
      and d.owner_id = (select auth.uid()))
);

alter policy "Avatar dog delete own dog path" on storage.objects to authenticated
using (
  bucket_id = 'dog-avatars'
  and cardinality(storage.foldername(objects.name)) = 3
  and (storage.foldername(objects.name))[1] = (select auth.uid())::text
  and (storage.foldername(objects.name))[2] = 'dogs'
  and exists (select 1 from public.dogs d
    where d.id::text = (storage.foldername(objects.name))[3]
      and d.owner_id = (select auth.uid()))
);

commit;
-- Explicitly approved by the user and applied on 2026-10-11.
-- LiveDogAvatarDeviceTest passed against the repaired backend after application.

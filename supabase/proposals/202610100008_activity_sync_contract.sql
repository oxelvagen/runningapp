-- Historical review snapshot. Approved/applied 2026-10-10; canonical copy is in migrations/.
-- Fixes the verified Android <-> production schema mismatch (PGRST204 / 42703).
begin;
alter table public.activities add column if not exists note text;
alter table public.activities add constraint activities_note_max_500
  check (note is null or char_length(note) <= 500) not valid;
alter table public.activities validate constraint activities_note_max_500;
-- Keep existing owner-only activity/point/split RLS. Notes are never part of social RPCs.
notify pgrst, 'reload schema';
commit;

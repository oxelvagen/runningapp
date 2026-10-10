-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 3, 1, '1.0.2',
  'v1.0.2', 'MinTracker-v1.0.2.apk', 'af5817fbbf58b9a8cb8c83ff2ad36f5007bbf19ecbb055fec8a4baef3bb4031e', 'Nyheter i 1.0.2:
• Återställ glömt lösenord och välj nytt lösenord direkt i appen.
• Frivillig inloggning med fingeravtryck eller säker ansiktsigenkänning.
• Ny biometriinställning under Profil → Inställningar → Säkerhet.
• Krypterad sessionslagring och säker rensning vid utloggning.')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
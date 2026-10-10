-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 5, 1, '1.0.4',
  'v1.0.4', 'MinTracker-v1.0.4.apk', '46371be8bd47863a72fc16a5335ee44d99391f135ffeae48d1550ea62dd33e8e', '# Min Tracker 1.0.4

- Rättad återställning av sparad inloggning vid omstart: ett tillfälligt nät- eller serverfel när sessionen förnyas skickar inte längre användaren till inloggningssidan.
- Den krypterade sessionen används för lokal åtkomst medan Supabase försöker förnya inloggningen i bakgrunden. Träning utan nät får fortfarande rätt konto som ägare.
- Utloggning och återkallade sessioner kräver fortfarande ny inloggning. Biometrisk låsning följer ditt befintliga val i inställningarna.

Installera APK:n över den befintliga appen. VersionCode: 5. Uppdateringen är frivillig.

Verifierat med riktiga testkonton och separata Androidprocesser: inloggning, kallstart, förnyelse av utgången åtkomsttoken, kallstart utan nät med utgången token, återställning när nätet återkommer samt utloggning följd av kallstart. Felet reproducerades före rättningen. Fysisk telefon har inte verifierats.
')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
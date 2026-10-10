-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 6, 1, '1.0.5',
  'v1.0.5', 'MinTracker-v1.0.5.apk', '69f544634a7f8a7cfc17c4d643c592f1568f1859ee4e6a1dc81d5ac7f4ebacba', '# Min Tracker 1.0.5 – Notiscenter

- Ny notisklocka på huvudskärmarna, med rött oläst antal och `99+` vid fler än 99.
- Samlat notiscenter med Alla/Olästa, profilbilder, fetstilta namn, relativ tid, händelseikoner, blå olästmarkering och Markera alla som lästa.
- Notiser för delade vänpass, distansrekord, tillåtna nya/avklarade utmaningar, hejarop, vänförfrågningar och gruppmedlemskap. Administrativa systemmeddelanden finns i samma lista.
- Sju separata kategorival under Profil → Inställningar → Notiser. Viktiga systemmeddelanden visas alltid.
- Serverkontroll vid öppning och en säker delad passdetalj. Privata pass, avslutad vänskap och raderat innehåll ger ingen fortsatt åtkomst. NPC-konton märks tydligt som simulerade.
- Uppdatering vid start, återgång till appen, öppning och varje minut i förgrunden. Ingen extern push eller Firebase krävs.
- Tidigare hämtade notiser kan visas under tillfälligt nätavbrott medan appen är igång. Cachen är kontobunden, sparas endast i minnet och rensas vid utloggning. Innehåll öppnas först efter ny serverkontroll.

VersionCode: **6**. Android 8.0 eller senare. Installera över den befintliga appen; samma paket och signatur används. Uppdateringen är frivillig.
')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
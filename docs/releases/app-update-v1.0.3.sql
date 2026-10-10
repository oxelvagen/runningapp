-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 4, 1, '1.0.3',
  'v1.0.3', 'MinTracker-v1.0.3.apk', '230c2bd1f79d9ec3be0a87d88f0fe1f97209b85e97044c828fce43198cf11799', '# Min Tracker 1.0.3

- Ny startsida under **Socialt → Aktiviteter**: vänners delade träning, personliga distansrekord och avklarade gemensamma utmaningar.
- Hjärtan/hejarop som kan läggas till och tas bort. Upprepad synkning ger inga dubbla händelser.
- **Privat/Vänner** under passresultatet, med ett separat val för att visa hundens namn. Synligheten kan ändras efter att passet synkats.
- **Profil → Inställningar → Delning och integritet**: automatisk delning av nya pass och offentlig profilstatistik. Båda är av som standard; äldre privata pass delas inte när automatisk delning aktiveras.
- Riktig statistik på offentliga profiler som tillåter det: delade pass, distans, tid, löpning/promenader, 7/30 dagar, viktat löptempo, längsta löppass, avklarade utmaningar och åtta veckors träning.
- Rättad synkning av pass med anteckningar och automatisk omladdning av utmaningsprogress efter bekräftad synkning/radering och återgång till appen.
- Serverkontrollerad integritet: privata pass räknas inte i offentlig statistik. GPS, rutter, anteckningar och exakta passtider lämnas inte ut i flödet. Borttagen vänskap återkallar åtkomsten.

Installera `MinTracker-v1.0.3.apk` över den befintliga appen. Samma paket och signatur som tidigare APK:er används. Uppdateringen är frivillig. Avinstallera inte appen inför uppdatering, eftersom lokala pass då kan försvinna.

VersionCode: **4**. Minsta Androidversion: **Android 8.0**.

Verifiering: 78 JVM-tester, 44 lokala backendtestgrupper, 28 vanliga Android-enhetstester på emulator och ett separat live-test av offlinekö, WorkManager-synkning, kontoisolering och radering mot Supabase. Live REST-tester använde tre testkonton för flöde, hejarop, integritetsändringar, profilstatistik och utmaningshändelser. Android Lint: 0 fel, 48 varningar.

Fysisk telefon, alla skärmstorlekar och större samtidig serverbelastning är inte verifierade. Biometriflödena från 1.0.2 har inte ändrats. En utförligare rapport finns i `docs/social-feed-verification.md`.
')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
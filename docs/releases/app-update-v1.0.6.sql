-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 7, 1, '1.0.6',
  'v1.0.6', 'MinTracker-v1.0.6.apk', '519e7f144a52d80e60e6105fab1a351443f628c22f2e2b993543829f8d337623', '# Min Tracker 1.0.6 – Stabilitet och Systemhälsa

- GPS-tjänsten startar före nedräkningen och registreringen fungerar oberoende av träningsskärmen.
- Pågående och avslutade men osparade pass sparas löpande i telefonens databas och kan återställas efter processavslut. Samma pass-ID används vid sparande för att undvika dubbletter.
- GPS-avbrott ger separata ruttsegment. Saknad sträcka och registreringstid fylls inte i med uppskattade värden.
- Korrigerad sessionshantering: tillfälliga nätfel och avbruten biometrisk upplåsning raderar inte inloggningen. Lösenord verifieras före ersättning av den skyddade sessionen.
- Förbättrad kartåterställning och lokal kartbakgrund som visar GPS-rutten utan internet.
- Ny teknisk felrapportering med offlinekö, kraschdiagnostik vid nästa start och Administration → Systemhälsa med grupper, filter, detaljer och status.
- Profil → Integritet och diagnostik beskriver datainnehåll och gallring och låter användaren stänga av rapporteringen. Lösenord, tokens, GPS-rutter och fria felmeddelanden skickas inte.

VersionCode: **7**. Android 8.0 eller senare. Uppdateringen är frivillig och använder samma paket och signeringscertifikat som tidigare beta. Installera ovanpå den befintliga appen utan att avinstallera eller rensa data.

Verifierat: 86 JVM-tester, 37 Android-tester på emulator, 16 SQL-testgrupper samt separata tester mot riktig Supabase för inloggning, offline felrapportering, kontrollerad krasch och adminbehörighet. Processåterställning har testats med SIGKILL och Force stop.

**Betastatus:** publiceras på användarens uttryckliga begäran. Det fysiska 30-minutersprovet med låst skärm och verklig GPS är ännu inte verifierat. Användaren utför detta test. Androids Force stop stoppar registreringen tills appen öppnas igen; inga saknade GPS-perioder kan återskapas.
')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
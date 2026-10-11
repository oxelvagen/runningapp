-- Generated locally. Does NOT publish or execute anything.
-- Upload the exact signed APK to its GitHub release first.
-- Confirm APK package, version and compatible certificate before running.
insert into public.app_update_config
  (id, latest_version_code, minimum_version_code, latest_version_name,
   github_release_tag, apk_asset_name, apk_sha256, release_notes)
values (true, 8, 1, '1.0.7',
  'v1.0.7', 'MinTracker-v1.0.7.apk', '0b6a8b3e330ea44a45b76ff5e4338ed4912f319d047cda439ea60a92d9ad8c43', '# Min Tracker 1.0.7 – GPS-stabilitet och hundbilder

- GPS-rutten behåller giltiga positioner även vid bilhastighet, mycket långsam rörelse och fördröjda leveranser. Hastigheten kapas inte längre vid 43,2 km/h.
- GPS-batcher som kommer i fel ordning sorteras utan dubbla punkter. Distans, ruttsegment, höjd och kilometertider räknas om för den aktuella registreringsdelen utan att koppla ihop pauser.
- Paus och stopp tömmer GPS-bufferten före det slutliga sparandet. Rörelse efter paus-/stoppgränsen räknas inte. Om bufferten inte kan tömmas inom tidsgränsen sparas mottagna punkter och en varning visas.
- GPS-punkterna ägs av en separat kö. Misslyckad köväckning kastar inte positionsdata.
- Löpande checkpoints skrivs i bakgrunden. Överflödiga mellanversioner slås ihop, medan paus och stopp väntar på en säker lagring.
- Mindre onödigt arbete under långa pass. Små positionssvängningar skapar inte falsk fart.
- Korrigerade privata lagringsbehörigheter för hundbilder, med fortsatt skydd mellan konton.

VersionCode: **8**. Android 8.0 eller senare. Uppdateringen är frivillig. Samma paket och signeringscertifikat som tidigare beta gör att den kan installeras ovanpå befintlig app utan att avinstallera eller rensa data.

GPS-standard är fortsatt hög noggrannhet och 3 sekunders önskat intervall. En jämförelse med 1 sekund gav fler punkter och mer beräkningsarbete; faktisk batteriförbrukning på Samsung är inte mätt.

Verifierat: 86 enhetstester, 55 Android-tester, separata processavslut/återställning och sex lokala RLS-regressionsgrupper. Lint: 0 fel. Efter den godkända serverkorrigeringen passerar även det separata Android-testet för hundbild: PNG till JPEG, uppladdning, sparad bildreferens, signerad hämtning, bildavkodning och borttagning.

Betastatus: automatiserade tester täcker GPS, skärmlåsning under nedräkning, bilhastighet, försenade batcher, paus/stopp och lagring. Fysisk Samsung med verklig GPS/Doze behöver fortsatt verifieras. Androids tvångsstopp eller ett avstängt GPS-system kan inte återskapa saknade positioner.
')
on conflict (id) do update set
  latest_version_code = excluded.latest_version_code,
  minimum_version_code = excluded.minimum_version_code,
  latest_version_name = excluded.latest_version_name,
  github_release_tag = excluded.github_release_tag,
  apk_asset_name = excluded.apk_asset_name,
  apk_sha256 = excluded.apk_sha256,
  release_notes = excluded.release_notes;
# Kritisk stabilitetsuppdatering – implementation och verifiering

Status: Android-delarna och felrapporteringen är implementerade. Användaren godkände serversteget med ”ok för mig kör”. SQL 202610110011 är körd och registrerad i det länkade Supabase-projektet 2026-10-11. Version 1.0.6, versionskod 7, publiceras som frivillig betauppdatering efter användarens uttryckliga instruktion ”tryck upp uppdateringen nu”. Användaren har valt att själv utföra telefonprovet. Den fysiska verifieringen är ännu inte genomförd; detta framgår i versionsinformationen.

APK: `MinTracker-v1.0.6.apk` i projektroten, identiska bytes med testfilen. SHA-256: `519e7f144a52d80e60e6105fab1a351443f628c22f2e2b993543829f8d337623`. Installera ovanpå den befintliga betan; avinstallera inte appen eller rensa dess data inför testet. Samma kompatibla debug/betacertifikat används. Ingen ny permanent signeringsnyckel har införts.

## Konkreta fynd

1. Den tidigare `TrackAccumulator` och `TrackingState` innehöll hela det aktiva passet enbart i processminne. Room skrevs först från sammanfattningsskärmens Spara. Ett instrumenteringstest startade den riktiga tjänsten och misslyckades eftersom ingen återställningsbar session fanns på disk. Testet passerar med den nya persistensen.
2. Den tidigare Compose-nedräkningen anropade START först när räknaren nådde noll. Tjänsten fanns därför inte under nedräkningen. Activity-återskapande tappade dessutom nedräkningens `remember`-state. Android kan neka en location-FGS-start när appen redan är i bakgrunden.
3. Tjänsten returnerade START_NOT_STICKY och hade ingen återställning för ett null-intent eller processdöd.
4. Avsluta tog en RAM-kopia och rensade tjänstens tillstånd innan användaren sparade. Även ett stoppat, osparat pass kunde därför försvinna vid processdöd.
5. Biometrisk upplåsning krävde refresh + retrieveUser och rensade sessionen vid alla vanliga exceptions. Ett tillfälligt nätfel kunde därför förstöra inloggningen. En manuell refresh kunde också konkurrera med SDK:ts tokenhantering.
6. Den gamla GPS-ackumulatorn kunde förbinda positioner över ett längre GPS-avbrott och saknade kontroll av identiska/äldre tidsstämplar.
7. Under en faktisk emulatoromkörning fastnade huvudtråden i `MapLibreSurfaceView.RenderThread.onWindowResize` när det aktiva passets karta återställdes offline. Det förhindrade även tjänstens startkommando. Ett trådprov lokaliserade väntan; samma återställningskedja passerade med TextureView. Tillfällig produktionsinstrumentering har tagits bort. Detta är verifierat på emulator, inte ett fastställt fel på den fysiska telefonen.
8. Kartstilen hämtades från nätet och sattes om medan den fortfarande laddades. Utan internet kunde det ge svart kartbakgrund trots sparad rutt. En lokal stil visar rutten utan nedladdade kartbilder, och samma pågående stilförfrågan startas inte om för varje GPS-punkt.
9. Lösenordsfallback från biometriläget rensade lagringen före lösenordskontrollen. Den verifierar nu först i en isolerad minnesklient och ersätter enbart efter lyckad autentisering.

Det finns ingen sparad kraschrapport från den rapporterade femkilometersrundan. Orsaken till att just den telefonens process avslutades är inte fastställd. Passet kan inte rekonstrueras om inga GPS-punkter någonsin skrevs till lagring. Fynden ovan är verifierade kodbrister, inte ett påstående om exakt processavslutsorsak på telefonen.

## Android-ändringar

- Location foreground service startas från Starta pass medan Activity är synlig, före nedräkningen. Inga ACCESS_BACKGROUND_LOCATION-behörigheter eller försök att tvinga appen till förgrunden har tillkommit.
- Tjänsten äger en beständig deadline för nedräkningen. Ett tidsbegränsat PARTIAL_WAKE_LOCK används endast för den korta nedräkningen, med timeout och explicit release. Skärmen tänds inte.
- Room 8→9 lägger till en lokal aktiv session per konto och segmentmarkering för lokala GPS-punkter. Ingen destruktiv migration används.
- Ett checkpoint innehåller UUID, konto, typ, hund, start/deadline, status, aktiv/pausad tid, distans, GPS-punkter med tider, kilometertider, mått, avbrott och stopptid. Hela snapshoten sparas atomiskt efter GPS-batcher, vid statusövergångar och normalt minst var femte sekund för tidtagningen.
- Statusflödet är COUNTDOWN → ACTIVE ↔ PAUSED → STOPPING → sparad aktivitet. Ett STOPPING-pass behålls på disk tills Spara lyckas eller användaren uttryckligen väljer att kasta det.
- Samma UUID används för checkpoint och ActivityEntity.cloudId. Spara är idempotent och slutaktivitet + GPS + splits + borttagning av checkpoint ingår i samma Room-transaktion.
- START_STICKY används för systemåterstart. Om Android inte tillåter återstart finns checkpointen kvar och återställning försöks från en synlig Activity när rätt konto öppnar appen. Ingen boot-receiver startar location-FGS otillåtet från bakgrunden.
- Processgap räknas inte in i aktiv tid eller distans. Första positionen efter återställning/paus är en ny ankare. Äldre/duplicerade GPS-tidsstämplar ignoreras. Avbrott över 30 sekunder börjar ett nytt ruttsegment och ger ingen sträcka mellan ändpunkterna.
- Tjänstens egen snapshot är källan för disk och stoppkommandon. Ett UI-state eller ett annat kontos sammanfattning kan inte skriva över ackumulatorn. Aktivt pass visas bara för dess ägare; ett annat konto kan inte starta över det. APK-installation blockeras medan något konto har ett pågående pass.
- Kartan använder TextureView för att undvika den reproducerade renderingsväntan. Utan nät eller vid stilfel visas en lokal bakgrund med GPS-linjen och en tydlig offlineetikett. Det tillkommer inga nedladdningskrav för att kunna visa själva GPS-rutten.
- Aktiv skärm visar återställnings-/GPS-meddelande samt både Pausa och Avsluta. Notisen öppnar pågående pass efter eventuell lokal säkerhetskontroll.
- Vanlig skärmlåsning nollställer inte appnavigation eller inloggning. Det befintliga frivilliga biometrivalet behålls. Lokalt upplåsning kräver ingen serverkontakt och importerar inte om en redan laddad session för samma konto.
- Supabase-SDK:t äger ordinarie refresh och rotation. Den befintliga krypterade lagringen och offline-kontokontexten behålls. Serveranrop kräver fortfarande giltig JWT/RLS; offline åtkomst ger inga serverbehörigheter.

GPS utan signal kan inte rekonstrueras. Tidscheckpoints innebär att de sista ännu inte skrivna sekunderna kan saknas efter ett abrupt processavslut. Ett uttryckligt Force stop stoppar tjänsten tills appen öppnas igen. Ingen Android-app kan lova omedelbar återstart efter alla typer av systemavslut.

## Inventering av felrapportering

En skrivskyddad inventering av det länkade Supabase-projektet hittade `admin_audit_log` och befintliga admin-RPC:er (`is_admin`, `_require_admin`, `admin_page`, `admin_dashboard`, `admin_command`). Det finns ingen befintlig tabell för klientfel. Auditloggen används för administratörsåtgärder och bör inte fyllas med obetrodda klientexceptions.

## Implementerad felrapportering

En avgränsad egen rapporteringskedja använder den befintliga Supabase-backenden och adminbehörigheten. Den skickar ett strikt begränsat format. Lösningen ersätter inte fullständig native crash-symbolik eller en etablerad plattformstjänst för alla typer av krascher.

1. En central reporter tar emot en fast teknisk kod, kategori, nivå och funktionsområde. Beskrivningar kommer från en kodkatalog. Den skickar aldrig exception.message, HTTP-body, URL, e-post, token, anteckning eller GPS. Stacken begränsas till appens klass/metodidentifierare, utan meddelanden eller parametrar.
2. Rapporter sparas i en separat Room-databas `diagnostics.db`, med kontoägare och anonymt installations-ID. Kön har högst 200 rapporter totalt, deduplicerar kod/sanerad stack inom tio minuter och gallrar efter 30 dagar. Högst åtta rapportoperationer får vänta i minnet. Fel prioriteras över INFO vid urval och kapning. Detta är begränsad och delvis samplad diagnostik; förekomstantalet visar mottagna rapporter och är inte en exakt räknare för varje händelse på telefonen.
3. WorkManager kräver anslutning och använder exponentiell backoff. Klienten skickar högst sex rapporter per batch för att hålla sig under serverns storleksgräns även med maximala stackar. Sex försök per rad och begränsade workerförsök hindrar oändliga återförsök. Uttömda rader förblir lokalt tills gallring, utan automatisk ny uppladdningsloop. Endast kvitterade UUID för det aktuella kontot raderas. HTTP-anropet låser det ursprungliga kontots JWT så att ett samtidigt kontobyte inte ändrar avsändaren. Uppladdningsfel rapporteras inte rekursivt.
4. UncaughtExceptionHandler skriver endast en liten atomisk lokal kraschspool, med sanerade klass/metodramar, och anropar alltid Androids tidigare handler. Ingen nätverks- eller Room-operation under kraschen. Vid nästa start importeras spoolen till kön. Rapporteringsfel fångas isolerat.
5. ApplicationExitInfo på API 30+ klassificerar verifierad Java/native-krasch och ANR separat från low-memory/user-requested/systemavslut. Rå description och rå traceStream skickas inte. Tidpunkt/processdiagnostik dedupliceras mot kraschspoolen. API 26–29 har inte denna källa. Alla processavslut kan inte upptäckas.
6. Sammanfattande träningsdiagnostik visar COUNTDOWN_STARTED, SERVICE_STARTED, GPS_STARTED, GPS_FIRST_FIX, SESSION_RESTORED, SERVICE_STOPPED, ACTIVITY_SAVED samt verkliga fel. Inte en rapport per GPS-punkt. INFO-händelser hålls utanför fel-/kraschmåtten.
7. Administration → Systemhälsa använder nya RPC:er för översikt, grupper, filtrering, kodsökning, detaljer och status. Klientuppgifter märks som obetrodd diagnostik. Statusändringar använder den befintliga admin-auditloggen.

### Godkänd SQL-migration

`supabase/proposals/202610110011_system_health.sql` skapar två tabeller (`app_error_groups`, `app_error_reports`), nya RLS-regler endast för dessa tabeller, fem RPC-/underhållsfunktioner och ett dagligt pg_cron-jobb för gallring. Befintliga aktivitets-, auth- och socialpolicyer ersätts inte.

- Vanliga användare får endast skicka via validerad RPC och får inte läsa tekniska rapporter, inte heller andra kontons data.
- Servern tilldelar auth.uid(); klienten får inte skicka användar-ID.
- Inga fria felmeddelanden accepteras. Fält, kategorier, koder, stackramar, version, modell, tider och storlek valideras. Högst 32 KiB/20 rapporter per batch, högst 20 nya rapporter/minut och 60/dygn per konto.
- Samma UUID kvitteras utan dubbelräkning. Fingerprint beräknas på servern. Förekomstantal från klienten är begränsat och ska betraktas som diagnostik, inte bevis.
- Rårapporter gallras efter 30 dagar. Inaktiva grupper gallras efter 90 dagar. Kontoradering raderar kontots rårapporter, inklusive installations-ID. Grupperade tekniska räknare utan individuell rapport kan finnas kvar tills gruppens gallring. Statushistorik registreras i befintlig auditlogg.
- Användarinformation i Profil → Integritet och diagnostik förklarar app-/Android-version, telefonmodell, anonymt installations-ID och kontoanknytning, att lösenord, tokens, GPS-rutt, privata anteckningar och bildinnehåll inte skickas, att endast behörig administration kan läsa rapporterna samt 30 dagars lagring av rårapporter.

**Migrationen är körd efter uttryckligt godkännande.** Den kanoniska filen ligger i `supabase/migrations/202610110011_system_health.sql`; granskningskopian behålls i `supabase/proposals`. Två nya tabeller, RPC:er, RLS och gallringsjobbet är installerade. Tidigare aktivitets-, auth- och socialpolicyer har inte ersatts.

Profil → Integritet och diagnostik beskriver datainnehåll, kontoanknytning, åtkomst och gallring och har en av/på-inställning. Avstängning stoppar insamling/uppladdning och rensar det aktuella kontots väntande kö. Kön, kraschfilen och installationsinställningarna är undantagna från Android-backup och enhetsöverföring.

Insamlingen är kopplad till träningssteg, återställning/sparande, GPS-start och signalavbrott, session-refresh, applåsfel, synkning, profilbildsuppladdning, bildladdning, skapande av utmaning, hejarop och uppdateringsfel. Vanlig coroutine-avbrytning rapporteras inte som fel. Kraschhanteraren skriver en atomisk spool och anropar föregående handler; det finns ingen kraschknapp eller testväxel i produktionsappen.

Administration → Systemhälsa innehåller 24-timmarsmått, uppdelningar per app-/Android-version och kategori, sökning, kategori-/nivå-/statusfilter, paginering, grupper, rapportdetaljer, sanerad stack och fyra statusar. Servern verifierar adminbehörigheten vid varje anrop.

## Verifiering

- `:app:testDebugUnitTest`: 86 tester, inga fel.
- `:app:assembleDebug` och `:app:assembleDebugAndroidTest`: passerade. Kandidaten är 1.0.6, versionskod 7, paket `se.oxelv.mintracker`, min API 26/target 35. APK-signaturen matchar den tidigare betans certifikat.
- `:app:connectedDebugAndroidTest`: 42 tester, 37 passerade och fem uttryckligen valfria integrationstester överhoppade i standardkörningen. Inga fel. Systemhälsas skärmtest, Room 8→9, befintliga pass, kontoseparering, servicepersistens, GPS-deduplicering/gap, nedräkning med låst emulatorskärm, Activity-återskapande och pausåterställning ingår.
- `:app:lintDebug`: noll fel, 58 varningar, bland annat API-föråldring, KTX-förslag och synkrona prefs-commits. De nödvändiga hållbarhetsmarkörerna skrivs synkront på IO-tråd.
- `Test-TrainingProcessRecovery.ps1`: passerade separat med riktiga processavslut via SIGKILL och Force stop, offline återställning av samma UUID, kontroll av aktiva skärmens reglage och exakt ett slutpass vid upprepat sparande. Testet tar bort sin egna lokala GPS-fixtur utan att synka en låtsasrutt till molnet.
- `Test-RememberedLogin.ps1`: alla tio faser passerade, inklusive felaktigt lösenord, processomstarter, tokenutgång, offline start, återanslutning och uttrycklig utloggning.
- `Test-Diagnostics.ps1`: passerade med riktig offlinekö och uppladdning, avstängning, deduplicering, processomstart, kontrollerad testkrasch, kraschimport och bibehållen session.
- Produktionens nya backend: verifierad migration i migrationsregistret, aktiverad RLS på båda tabellerna och aktivt gallringsjobb. Livekontroller bekräftade mottagen fel-/kraschrapport, vanlig användares nekade åtkomst och adminöversikt/detalj/status. Testets statusändring rullades tillbaka.

De dedikerade testkontonas diagnostiska testposter har rensats efter backendkontrollerna. Befintliga användarkonton, träningspass och sociala data har inte raderats.

Tidigare misslyckanden har rättats: det ursprungliga röda servicepersistenstestet; kartans reproducerade SurfaceView-hängning; testharnessproblem och en lintkontroll av platsbehörigheten. Den sista diagnostikomkörningen hittade även att en redan påbörjad startimport kunde återinföra en rapport efter avstängning. Den kontrollerar nu av/på-läget inne i den serialiserade Room-transaktionen. Ett explicit regressionstest simulerar den påbörjade importen medan diagnostiken är avstängd; hela livekedjan passerade därefter. Inga kvarstående fel i de avslutade automatiska körningarna.

Körloggar finns lokalt under `tmp/`: `stability-approved-v3.log`, `stability-optout-fix-build.log`, `stability-device-final.log`, `stability-process-final.log`, `stability-auth-final.log`, `stability-diagnostics-v2-final.log` och `stability-health-sql-final.log`.

Det lokala SQL-testet `tools/backend-tests/system-health-proposal.mjs` har 16 testgrupper för behörighet, idempotens, gruppering, sanitetsvalidering, rate limiting, status/audit, filtrering, kontoradering och gallring. Det körs mot lokal PGlite/PostgreSQL, inte produktionsprojektet.

### Återstår på fysisk telefon

ADB har endast en emulator ansluten. Ingen fysisk telefon har verifierats. Före release krävs minst 30 minuter med låst skärm och verklig GPS, rätt skärm efter upplåsning, biometrival/avbruten prompt/offline-upplåsning, paus/återupptagning, sparande och verklig Supabase-synkning samt energibeteende. Ett nattlångt verkligt test, telefonreboot och olika tillverkares bakgrundsbegränsningar återstår också.

Användaren har valt att själv testa med test-APK:n. Installera den ovanpå nuvarande beta och behåll appdata. Starta ett pass, lås skärmen redan under nedräkningen och registrera minst 30 minuter med riktig GPS. Lås upp, kontrollera samma pågående pass, tid, distans och rutt; pausa/återuppta, avsluta och spara. Kontrollera historiken och molnsynkningen. Stäng och öppna appen igen och kontrollera bibehållen inloggning. Prova också offline-start och det frivilliga biometrilåsets avbryt-/upplåsningsflöde. Rapportera telefonmodell/Android-version och observerat resultat; dela inte lösenord eller tokens.

Hanterad felrapport, offlinekö, avstängd diagnostik, deduplicering, processöverskridande kö, återupptagen uppladdning, kontrollerad testkrasch, import vid nästa start och kvarvarande inloggning har verifierats i emulator mot den riktiga Supabase-backenden. Produktions-RLS, adminöversikt, rapportdetaljer och statusändring har verifierats i en SQL-transaktion; testets statusändring rullades tillbaka. Verklig användning under långvarig GPS-registrering och rapporteringsfel på olika telefoner behöver fortfarande kontrolleras fysiskt.

## Ändrade filer

Android-källorna ligger under `app/src/main/java/se/private/mintracker/` (Kotlin-paketet är `se.oxelv.mintracker`).

- Registrering/återställning: `tracking/TrackingService.kt`, `tracking/TrackingState.kt`, nya `tracking/RouteSegments.kt`, nya `data/ActiveTraining.kt`, `data/LocalData.kt`, `data/TrackerDatabaseProvider.kt`, `data/ActivityRepository.kt`, `MainActivity.kt`, `ActivityScreens.kt`, `MapExportRenderer.kt`.
- Inloggning: `data/SecureSessionManager.kt`, `data/AppSecurity.kt`, `data/AuthRepository.kt`, `AuthSecurityScreens.kt`.
- Rapportering/administration: nya `data/Diagnostics.kt` och `SystemHealthScreen.kt`, `data/AdminRepository.kt`, `AdminScreen.kt`, `ProfileScreen.kt`, `AvatarImage.kt`, `ChallengeScreens.kt`, `SocialActivityScreens.kt`, `data/ActivitySyncWorker.kt`, `updates/AppUpdateManager.kt`.
- Android-konfiguration: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`.
- Backend: nya `supabase/migrations/202610110011_system_health.sql` och granskningskopian i `supabase/proposals/`.
- Tester: nya `DiagnosticSanitizerTest.kt`, `DiagnosticQueueDeviceTest.kt`, `LiveDiagnosticsDeviceTest.kt`, `SystemHealthUiTest.kt`, `TrainingDurabilityDeviceTest.kt`, `ActiveTrainingDatabaseTest.kt`, `TrainingProcessDeviceTest.kt`; ändrade `SecureSessionDeviceTest.kt` och `RememberedLoginDeviceTest.kt`.
- Körskript: nya `tools/backend-tests/Test-Diagnostics.ps1`, `Test-TrainingProcessRecovery.ps1` och `system-health-proposal.mjs`; ändrade `Test-RememberedLogin.ps1`.
- Dokumentation: denna rapport och `docs/auth-security.md`.

## Plattformskällor

- [Android: begränsningar för foreground services från bakgrunden](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android: ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
- [Android: tidsbegränsade partial wake locks](https://developer.android.com/topic/performance/issues/stuck-wakelock)
- [Google: FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient)

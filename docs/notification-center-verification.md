# Notiscenter 1.0.5 – leverans och verifiering

Migration 010 godkändes av användaren, installerades i det länkade Supabase-projektet och registrerades i migrationshistoriken 2026-10-10. Äldre notisrader och administrativa utskick behålls. Den gamla passgeneratorn har tagits bort eftersom den saknade kontroll av passets delning.

## Implementation

- `data/NotificationModels.kt`: typade notiser, mål, inställningar, paginering och svensk text/tid/badge.
- `data/NotificationCenterRepository.kt`: kontobunden RPC-åtkomst med kontroll före och efter anrop, gemensam räknare och minnescache. Nätanrop serialiseras. Cachen rensas när kontots appinnehåll lämnas.
- `NotificationScreens.kt`: klocka, listan Alla/Olästa, läsmarkering, 20-raders paginering, inställningar och säker delad passdetalj.
- `MainActivity.kt`: klocka på huvudskärmarna, en livscykelstyrd uppdateringsloop, navigation och återkontroll av gruppmedlemskap vid gruppöppning/återgång till appen.
- `AdminScreen.kt`, `data/AdminRepository.kt`: den äldre parallella inkorgen har ersatts.
- `supabase/migrations/202610100010_notification_center.sql`: serverhärledd inkorg, stabila mottagarspecifika UUID:n, läskvitton, kategorival, aktuell synlighet och begränsad passdetalj. Ny RLS-policy skyddar även den gamla notistabellen.

## Verifierat

- 84 JVM-tester passerar, inklusive sex nya tester av notistext, tidsgränser, `99+`, kategorier och JSON-format.
- 63 lokala SQL-testgrupper passerar: 19 notisgrupper, 18 sociala grupper och 26 befintliga backendgrupper.
- `Test-LiveNotifications.ps1` kördes mot tre riktiga testkonton: 39 PASS-kontroller för privata/delade pass, rekord, stabila ID:n, läsning/räknare, hejarop, inställningar, vänskapsåterkallelse, förfrågningar, privata/tillåtna utmaningar, avklarad utmaning, grupper och radering. Tillfälliga pass, utmaningar och grupper togs bort och kontonas inställningar/vänskap återställdes. Testkontonas lässtatus uppdaterades av lästesterna.
- `Test-LiveNotificationNpc.ps1` verifierade NPC-märkning, begränsad passdetalj, privata NPC-pass och återkallad vänskap i riktiga PostgreSQL, i en transaktion som återställdes helt.
- Separat live-test på Androidemulator passerar: faktisk huvudskärm → klocka → notislista → inställningar → delat pass → offlinevisning → kontobyte. Testet kontrollerar lässtatus på servern och att en gammal repository inte kan läsa efter kontobyte. Temporära uppgifter och testpass städas bort.
- Den vanliga instrumenterade Androidsviten: XML-resultat 31 tester, varav 28 passerar och tre uttryckliga live-tester hoppas över när deras tillfälliga indata saknas. Inga fel. Live-notistestet kördes separat med indata.
- APK byggd med paket `se.oxelv.mintracker`, versionName `1.0.5`, versionCode `6`, minSdk 26 och targetSdk 35. Signaturens SHA-256 matchar tidigare leveranser: `5153574877e79da8d415e4b365a5428d838b74c9a93b36072ac94795300bc4e4`.
- Android Lint: 0 fel, 49 varningar. Vyerna för lista, inställningar och pass har granskats visuellt på emulator.

Kommandon och tester finns i `tools/backend-tests/notification-proposal.mjs`, `Test-LiveNotifications.ps1`, `Test-LiveNotificationNpc.ps1`, `Invoke-LiveNotificationDeviceTest.ps1` och Androidtestet `data/LiveNotificationDeviceTest.kt`. Inga testlösenord eller tokens finns i källkod eller APK; testuppgifter provisioneras till privat applagring och tas bort direkt.

## Begränsningar

- Ingen extern Android-push byggs. Uppdateringen sker vid start, återgång, öppning, användarens åtgärder och var 60:e sekund medan appen är i förgrunden.
- Offlinecachen finns bara i minnet för det aktuella kontot och överlever inte processavslut. Äldre data märks tydligt; öppning kräver en ny serverkontroll.
- Sociala händelser återanvänder flödets timavrundade tidsstämplar; relativ tid är därför grov för dessa händelser.
- Härledda notiser följer den aktuella källan. En accepterad förfrågan försvinner ur mottagarens väntande notiser. Ett borttaget hejarop försvinner, och återlagt hejarop återanvänder notis-ID/lässtatus. Nya medlemsnotiser skickas bara till gruppägaren, inte alla medlemmar.
- Ingen blockeringstabell finns i nuvarande schema. Befintlig vänskap, delning, gruppmedlemskap, profilspärr och arkivering kontrolleras.
- Fysisk telefon, alla skärmstorlekar och hög samtidig serverbelastning är inte verifierade. Den härledda inkorgens räknare behöver belastningsmätas vid större användarantal.

Testverktyget Espresso uppdaterades för emulatorns Androidversion enligt AndroidX officiella versionsinformation: https://developer.android.com/jetpack/androidx/releases/test . Produktionsfunktionerna kräver inte Espresso.

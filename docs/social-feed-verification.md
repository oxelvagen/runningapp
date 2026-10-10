# Socialt flöde – implementation och verifiering

Datum: 2026-10-10. Version 1.0.3, versionCode 4.

## Databas

Användaren godkände granskningen och bad uttryckligen om uppdatering/publicering. Migrationerna 008 och 009 kördes sekventiellt mot det länkade Supabase-projektet, och migrationshistoriken registrerades som tillämpad. De ursprungliga granskningsfilerna bevaras under `supabase/proposals`; de tillämpade filerna ligger under `supabase/migrations`.

008 lade till `activities.note` med gräns på 500 tecken. 009 införde sociala integritetsinställningar, synlighet per pass, händelser, hejarop, serverstyrd utmaningslivscykel, statistik-RPC och en restriktiv profilpolicy. Standardinställningarna delar ingen träning automatiskt och visar ingen offentlig statistik. Ingen historisk massdelning eller backfill genomfördes.

Reglerna i granskningsunderlaget gäller: accepterade vänner i flödet; privat/offentlig statistik skiljs åt; endast delade pass bidrar till offentlig statistik. Privata utmaningar publiceras aldrig. Gemensamma utmaningshändelser kräver både samtycke och deltagar-/vänbehörighet. Privat underlag kan påverka officiell progress men publiceras inte som passdetaljer.

## Android

`SocialFeedRepository` använder autentiserade RPC:er och kontrollerar kontot före och efter anrop. Flödes- och inställningsinnehåll ligger i kontobundna Compose-träd; det finns ingen global cache med andra användares sociala innehåll. Den globala signalen innehåller endast kontobundna revisionsräknare.

Aktivitetssynkning går nu genom `sync_social_activity`; GPS-punkter och kilometertider synkas fortsatt separat med befintlig ägar-RLS. Anteckningsändringar använder den befintliga lokala revisionskontrollen. Efter bekräftad synkning/radering signaleras omladdning av utmaningar och sociala vyer. Återgång till appen omvaliderar synligt socialt innehåll. WorkManagers nätverkskrav, lokala kö och raderingstombstones finns kvar.

Socialt öppnar Aktiviteter med nyckelbaserad paginering om 20 händelser, serverbegränsad åtkomst och svenska texter från strukturerad data. Hejarop väntar på serverbekräftelse. Integritetsinställningar och passens delningsval använder serverrevisioner; ett inaktuellt val ger fel och kräver omläsning i stället för att skriva över nyare inställningar. Passens synlighet ändras efter bekräftad synkning; den automatiska standarden styrs av serverns samtyckesgräns.

Profilstatistik har laddning, privatläge, tomvy, fel och omläsning. Offentlig profil plus separat statistikmedgivande krävs även för vänner. Tids- och distanssummor, aktivitetstyper, 7/30 dagar, viktat löptempo, längsta löppass, avklarade utmaningar och åtta veckobalkar renderas med appens kort, färger och typografi.

## Automatiska tester

- `node tools/backend-tests/run.mjs`: 26 befintliga backendtestgrupper godkända, inklusive migrationerna 008/009.
- `node tools/backend-tests/social-proposals.mjs`: 18 sociala testgrupper godkända mot de tillämpade migrationsfilerna i lokal PGlite. Bland annat privat/vän/icke-vän, återkallad vänskap, direkt tabellåtkomst, dubletter, hejarop, rekordhistorik, offentlig statistik, versionskonflikter, måluppfyllelse/radering/ny måluppfyllelse, paginering och kaskadradering.
- `:app:testDebugUnitTest`: 78 JVM-tester godkända, inklusive fyra nya sociala kontraktstester för serverpayload, nullable utmaningsdata, privat statistik och svenska texter.
- `:app:assembleDebug` och `:app:assembleDebugAndroidTest`: APK och test-APK byggda.
- `:app:connectedDebugAndroidTest`: befintliga 28 tester godkända på Medium Phone API 37. Det nya live-testet är opt-in och hoppas över i vanlig körning utan tillfälliga testuppgifter; det kördes separat med riktiga konton enligt nedan.
- `:app:lintDebug`: 0 fel, 48 varningar.

## Live REST-tester

`Test-LiveChallengeProgress.ps1` kördes igen efter migrationen, nu med det normala anteckningsfältet. Två riktiga testkonton: 3 km av 10 km gav 30 %, ytterligare 2 km gav 50 %, fel typ och pass utanför perioden räknades bort. Radering minskade distans, antal, duration och höjdmeter. Det andra kontot nekades privat topplista.

`Test-LiveSocialFeed.ps1` använde tre testkonton genom vanliga användartoken. Privat pass doldes, uttryckligen delat pass syntes för vän, icke-vän nekades, upprepad synkning skapade inga dubletter och hejarop var idempotenta och borttagbara. Testet kontrollerade att inga råa passrader eller känsliga fält lämnades ut, att integritetsändring och vänskapsborttagning återkallade åtkomst, att offentlig statistik endast räknade delade pass och att privat profil/statistikavstängning skyddades även från vänner. En servergenererad utmaningshändelse skapades en gång, doldes när målpasset raderades och återanvände samma ID vid ny måluppfyllelse.

Temporära pass och utmaningar togs bort. Testkontonas ursprungliga profil-/integritetsval och vänskapsstatus återställdes. Versionsräknare ökar avsiktligt även vid testernas återställning; tillfälliga vänförfrågningar kan finnas i testkontonas historik.

## Live Android/offline

`LiveSocialSyncDeviceTest` kördes separat på emulatorn med två riktiga testkonton. Tillfälliga uppgifter provisionerades från DPAPI-skyddad lokal lagring till debug-appens privata katalog och raderades direkt efter inläsning. Inga lösenord eller token byggdes in i APK:n eller skrevs ut.

Testet stängde av wifi och mobildata, verifierade att nät saknades, sparade ett pass med anteckning/GPS/kilometertid i Room och köade den riktiga WorkManager-arbetaren. Passet låg kvar som PENDING offline. När nätet återkom blev det SYNCED, anteckningen återlästes från Supabase, passet förblev privat och serverns utmaning visade 3 000 meter/30 %. Efter kontobyte kunde B inte läsa A:s råa serverpass eller lokala historik, och en repository-instans för det gamla kontot nekades. Radering via lokal tombstone och WorkManager tog bort molnpasset och gav 0 %.

Testet passerade. Den verkliga aktivitetsvyns tomläge fångades och inspekterades visuellt på emulatorn. Navigationsfångsten använder fasta emulatorpositioner och är visuell granskning; den är inte en automatisk semantisk UI-assertion. Tillfälliga aktiviteter/utmaningar och inloggningar städades bort.

## Kvarvarande begränsningar

Ingen fysisk telefon eller fullständig matris av skärmstorlekar har testats. PGlite och emulatorn verifierar inte större produktionsbelastning eller alla samtidighetsfall. Gruppmedlemskap och hundnamn har serverskydd och implementation men ingick inte i live-matrisen med testkonton. GPS-insamling under ett verkligt utomhuspass och Androids installationsdialog för den publicerade uppdateringen återstår att prova på telefon. Uppdateringen behåller tidigare APK-certifikat för installation över befintlig version.

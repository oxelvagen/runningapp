# Min Tracker

Svensk Androidapp för löpning, promenader, hundpass och social träning. Kotlin, Jetpack Compose, Room och Supabase.

## Installera

Hämta APK:n från [GitHub Releases](https://github.com/oxelvagen/runningapp/releases/latest). Installera över befintlig app för att behålla lokala data. Version 1.0.1 och senare kan också visa uppdateringen direkt i appen.

## Bygg

JDK 17 och Android SDK 35 krävs. Sätt `ANDROID_HOME` eller ange SDK-sökväg i en lokal, ignorerad `local.properties`.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Det sista kommandot kräver en ansluten Android-enhet eller emulator. Android Studio kan också öppna projektet direkt. Supabase-projektet är redan konfigurerat för denna app; klienten innehåller en publik publishable-nyckel, aldrig servernycklar.

## Version 1.0.3

Socialt aktivitetsflöde, hejarop, integritetsval per pass, offentlig profilstatistik och rättad synkning/utmaningsprogress. Se [release notes](docs/releases/v1.0.3.md), [verifieringsrapporten](docs/social-feed-verification.md) och [uppdateringssystemet](docs/app-updates.md). Lösenordsåterställning och frivillig biometri från 1.0.2 finns kvar.

APK-leveransen behåller tidigare debug-signatur för kompatibilitet med befintliga installationer. Privata signeringsnycklar ingår inte i detta repository. En lokal ombyggnad med en annan debugnyckel kan inte installeras som uppdatering över den publicerade APK:n.

Verifiering: 78 JVM-tester, 44 lokala backendtestgrupper, 28 vanliga enhetstester på emulator och ett separat live-test av offlinekö/WorkManager/kontobyte/radering. Sociala REST-flöden verifierades med tre Supabase-testkonton. Lint: 0 fel, 48 varningar. Fysisk telefon och uppdateringsinstallation via Androids dialog återstår att prova.

Migrationerna 008/009 är tillämpade i appens befintliga backend och förutsätter dess tidigare utmanings-/adminschema; de är inte en fristående Supabase-installation. Live-testskripten kräver lokalt provisionerade testkonton. De innehåller inga inloggningsuppgifter.

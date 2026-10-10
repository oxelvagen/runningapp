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

## Version 1.0.2

Lösenordsåterställning och frivillig biometrisk inloggning. Se [release notes](docs/releases/v1.0.2.md), [auth och verifiering](docs/auth-security.md) och [uppdateringssystemet](docs/app-updates.md).

APK-leveransen behåller tidigare debug-signatur för kompatibilitet med befintliga installationer. Privata signeringsnycklar ingår inte i detta repository. En lokal ombyggnad med en annan debugnyckel kan inte installeras som uppdatering över den publicerade APK:n.

Automatisk verifiering: 74 JVM-tester godkända, debug-APK och instrumenteringstester byggda, Lint utan fel. Verklig biometri, recovery-mejlflöde och uppdateringsinstallation återstår att kontrollera på Android-telefon eftersom ingen enhet var ansluten under bygget.

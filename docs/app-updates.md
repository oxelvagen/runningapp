# Uppdateringar via GitHub Releases

## Leverans

Repository: `oxelvagen/runningapp` (publikt, verifierat). Konfigurationen finns i app/build.gradle.kts och kan ersättas vid build med `-PupdateRepository=owner/repository`. Application ID är kvar `se.oxelv.mintracker`.

Den första APK:n med uppdateringssystemet är 1.0.1, versionCode 2. Den är debug-signerad med samma certifikat som föregående MinTracker.apk. Installera den manuellt en gång på telefoner som har 1.0: äldre APK:er innehåller inte det nya systemet och kan inte få det genom enbart en serverinställning.

Den godkända migrationen 202610100007 är installerad i Supabase. Tabellen är tom tills en riktig release har publicerats. Ingen GitHub Release eller versionspolicy har publicerats automatiskt. En tom tabell ger status att ingen uppdateringsversion publicerats, inte ett falskt besked om uppdateringskrav.

Leveransfil: MinTracker-v1.0.1.apk (versionCode 2), cirka 75 MB. MinTracker.apk är samma fil. SHA-256 finns i MinTracker-v1.0.1.apk.sha256. Förberedd frivillig versionspolicy med exakt filhash finns i docs/releases/app-update-v1.0.1.sql; kör den först efter att just denna APK publicerats i taggen v1.0.1. Filen är fortfarande en debug-signerad testleverans.

## Appens flöde och gränser

- Kontroll vid processstart, återgång till förgrunden högst en gång per 15 minuter och manuell kontroll under Profil → Inställningar → Appversion och uppdateringar. Manuella kontroller kringgår tidsgränsen.
- Kontrollen är oberoende av Supabase-inloggning och underhållsläge: separat HTTPS-anrop med publishable-nyckeln och utan användarens JWT. Ingen hemlig nyckel används i appen.
- Nyare latest_version_code visar frivillig dialog. Endast minimum_version_code styr den nya tvingande spärren, även för admin och före inloggning. Den gamla klientspärren för app_settings.minimum_app_version används inte längre i nya klienten; äldre klienter har kvar sitt tidigare beteende. Hantera nya versioner i app_update_config.
- Misslyckad första kontroll utan cache tillåter normal start med okänd status. Tidigare verifierad policy sparas i privat lagring. Dess miniminivå upphör inte bara för att telefonen är offline. Högre serverrevision kan ändra policyn; gamla eller motstridiga svar avvisas. Senast verifierad tid visas. Ett misslyckat nätanrop markerar kontrollen som misslyckad utan att radera policy.
- Frivillig dialog från cache visas automatiskt bara om policyn verifierats de senaste sju dagarna. Senare skjuter upp samma frivilliga revision i 24 timmar, även över processomstart; manuell kontroll kringgår detta. Dessa tidsgränser tar aldrig bort en tvingande miniminivå.
- DownloadManager hämtar APK från den exakta releasens exakt namngivna asset. Systemet fortsätter överföringen när appen stängs, väntar vid nätverksavbrott och återansluter till samma jobb efter processstart. Avbrutna/borttagna/misslyckade jobb kan startas om. Ingen egen HTTP-resume implementeras utöver Androids DownloadManager.
- Nedladdad APK kopieras till appens interna privata katalog, verifieras och verifieras igen omedelbart före installation. Kontroller: exakt SHA-256 från Supabase, paketnamn, versionCode större än installerad och exakt lika med konfigurationen, exakt versionName samt kryptografiskt giltig APK-signatur med apksig.
- Signeringsrotation accepteras endast framåt för en signerare på Android 9+, med verifierad lineage som inkluderar installerad aktuell signerare och installed-data capability. Android 8 kräver samma signerare. Multisignering kräver exakt signerarmängd. Androids installerare avgör dessutom slutlig plattformskompatibilitet och kan neka installationen.
- APK:n lämnas via begränsad FileProvider content-URI och tillfällig läsrättighet. REQUEST_INSTALL_PACKAGES behövs för det direkta installationsflödet utanför Play. Androids tillstånd för denna källa öppnas vid behov; användaren måste därefter själv välja installation och godkänna Androids dialog. Appen installerar aldrig tyst.
- När nedladdningen är verifierad visas Installera uppdatering. Om användaren avbryter installeraren ligger den verifierade filen kvar för nytt försök. En tvingande policy förblir blockerande. Efter lyckad ersättning startas nya APK:n med sitt nya versionCode och beräknar status igen.
- Upptäcks tvingande policy under ett pågående pass fortsätter registreringen och endast pass-/sparflödet visas. Uppdateringen blockerar vanliga funktioner efter lyckad lokal Room-sparning; molnsynk kan köras vidare via befintlig worker. Installation erbjuds inte med osparat pass. Ingen ny automatisk avslutning av GPS-tjänsten görs.

## Signering – beslut krävs innan permanent nyckel skapas

Ingen permanent nyckel har skapats eller ersatts. Release-signering är förberedd i Gradle med miljövariabler; packageRelease vägrar skapa en leverans när signeringskonfiguration saknas. Nuvarande certifikat SHA-256:

`5153574877e79da8d415e4b365a5428d838b74c9a93b36072ac94795300bc4e4`

En ny release-nyckel blir inte automatiskt kompatibel med dagens debug-installationer. Bestäm först hur befintliga användare ska migreras och bevara/exportera lokal träningsdata innan eventuell ominstallation. Debug-nyckeln används bara för denna kompatibla testleverans; använd en konsekvent skyddad release-identitet för framtida betadistribution efter godkänd strategi.

Keystore/lösenord får aldrig läggas i Git. `.gitignore` omfattar *.jks, *.keystore, keystore.properties och APK:er. Säkerhetskopiera den godkända keystoren krypterat på minst två separata säkra platser, lagra lösenord i lösenordshanterare och kontrollera att återställning fungerar. Förlust av nyckeln kan stoppa framtida kompatibla uppdateringar.

## Bygga en framtida signerad release

Projektet saknar gradlew-wrapper. På denna dator används följande Gradle/JDK. T: är den befintliga sökvägsmappningen till projektet som undviker JVM-problem med ö i sökvägen.

```powershell
$env:JAVA_HOME = 'C:\Users\oxelv\.cache\mintracker-tools\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\Users\oxelv\AppData\Local\Android\Sdk'
$gradle = 'C:\Users\oxelv\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat'
Set-Location T:\
```

När en keystore redan är godkänd och finns säkert lagrad: ange dess sökväg/alias och läs lösenorden interaktivt utan att skriva dem i kommandoradshistoriken. Exemplet skapar ingen nyckel.

```powershell
$env:MINTRACKER_KEYSTORE = Read-Host 'Absolut sökväg till godkänd keystore'
$env:MINTRACKER_KEY_ALIAS = Read-Host 'Nyckelalias'
$env:MINTRACKER_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Keystore-lösenord' -AsSecureString)).Password
$env:MINTRACKER_KEY_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Nyckellösenord' -AsSecureString)).Password
try {
    & $gradle :app:testDebugUnitTest :app:assembleRelease '-PappVersionCode=3' '-PappVersionName=1.0.2' '-Pkotlin.incremental=false'
    if ($LASTEXITCODE -ne 0) { throw 'Bygget misslyckades' }
} finally {
    Remove-Item Env:MINTRACKER_STORE_PASSWORD, Env:MINTRACKER_KEY_PASSWORD -ErrorAction SilentlyContinue
}
Copy-Item app\build\outputs\apk\release\app-release.apk MinTracker-v1.0.2.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --print-certs MinTracker-v1.0.2.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt.exe" dump badging MinTracker-v1.0.2.apk
```

Höj alltid versionCode strikt (2 → 3 → 4). versionName är visningsversionen och måste matcha policyn exakt. Kontrollera apksigner-resultatet mot den identitet användarna redan har innan publicering. Använd inte assembleRelease med dagens debugnyckel som en oannonserad permanent signeringsstrategi.

## Publicera på GitHub och sedan i Supabase

1. Öppna https://github.com/oxelvagen/runningapp/releases/new. Skapa taggen v1.0.2 för motsvarande källkod, ange versionsnamn och svenska release notes. Ladda upp den redan signerade filen MinTracker-v1.0.2.apk och publicera releasen. Den får inte vara ett utkast. Ersätt inte en publicerad APK med andra bytes under samma versionsidentitet.
2. Kontrollera att den publicerade releasen och APK-filen går att nå utan GitHub-inloggning. GitHub API-adressen är `https://api.github.com/repos/oxelvagen/runningapp/releases/tags/v1.0.2`.
3. Beräkna SHA-256 på den exakta filen du laddade upp: `(Get-FileHash -LiteralPath .\MinTracker-v1.0.2.apk -Algorithm SHA256).Hash.ToLowerInvariant()`.
4. Generera granskningsbar SQL lokalt. Skriptet laddar inte upp eller kör någonting:

```powershell
.\tools\New-AppUpdatePolicy.ps1 -ApkPath .\MinTracker-v1.0.2.apk `
    -VersionCode 3 -MinimumVersionCode 2 -VersionName '1.0.2' `
    -ReleaseTag 'v1.0.2' -ReleaseNotes "Nyheter och buggfixar" `
    -OutputPath .\app-update-v1.0.2.sql
```

5. Granska SQL-filen och kör den i rätt projekts Supabase SQL Editor. Använd UPDATE/upsert av id=true; radera inte raden, eftersom revisionsräknaren ska behållas. Inga åtkomsttoken ska publiceras. Den faktiska migrationen finns redan installerad.
6. Frivillig uppdatering för installerad versionCode 2: latest=3 och minimum=2 (eller 1). Tvingande för versionCode 2: latest=3 och minimum=3. minimum får aldrig överstiga latest. Höj minimum först efter att nedladdning, signering och installation verifierats på testtelefoner.
7. Testa frivilligt flöde, obligatoriskt flöde, offline med cache, avbruten installation, fel checksumma och pågående pass innan utrullning till alla. Testa gärna från en version lägre än latest med samma signeringsidentitet på en separat testenhet.

## Ändrade filer och verifiering

Uppdateringskomponenter finns under app/src/main/java/se/private/mintracker/updates: modeller/regler, repository, manager, DownloadManager-adapter, APK-verifierare och Compose-skärmar. MainActivity ansluter lifecycle, inställningar och passkydd. ActivityScreens har en valfri allowDiscard-parameter för att skydda osparat pass vid tvingande policy. Manifest och share_paths innehåller installationsbehörighet respektive begränsad privat APK-katalog. Gradle innehåller repository, versionering, apksig och förberedd release-signering.

JVM-tester: AppUpdateRulesTest. Enhetstester: AppUpdateDeviceTest för policycache, avbrutet DownloadManager-jobb, kryptografisk APK-kontroll och content-URI. Instrumentering kräver ansluten telefon/emulator. JVM-regeltester verifierar inte faktisk Androidinstallation, permissionsgränssnitt eller signeringsrotation på enhet; dessa kräver testleveranser och Androids installerare.

Slutkontroll 2026-10-10: testDebugUnitTest passerade med 71 tester (20 nya uppdateringsregeltester), assembleDebug och assembleDebugAndroidTest passerade. lintDebug passerade med 0 fel och 39 varningar. Backendinstallationen med samtliga sju migrationer passerade 26 lokala SQL-testgrupper inklusive uppdateringstabellens RLS/revision/validering. Publik HTTPS-läsning av den nya tabellen returnerade HTTP 200 och tom lista. APK-signaturen verifierades med apksigner och samma certifikat som föregående leverans. connectedDebugAndroidTest kördes men kunde inte utföra testerna: No connected devices. Ingen emulatorprofil finns konfigurerad. Full installation, avbrott, återstart och signeringsrotation måste därför verifieras på en ansluten testenhet innan skarp utrullning.

Referenser: https://docs.github.com/en/rest/releases/releases, https://developer.android.com/studio/publish/app-signing, https://android.googlesource.com/platform/tools/apksig/, https://developer.android.com/reference/android/app/DownloadManager, https://supabase.com/docs/guides/database/postgres/row-level-security.

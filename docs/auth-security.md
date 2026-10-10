# Lösenordsåterställning och biometri – 1.0.2

## Auth och återställning

Supabase Kotlin Auth 3.2.3 använder implicit-flödet. Projektets befintliga tillåtna redirect och Android intent-filter är `mintracker://auth/callback`. Ingen Dashboard-ändring eller databasmigration behövs för dessa funktioner. Recovery identifieras med `type=recovery` i fragmentet och importeras enbart i en separat klient med MemorySessionManager, utan lagring eller automatisk refresh. Registreringslänkar fortsätter genom ordinarie SDK-hantering.

Dashboard → Authentication → Sign In / Providers → Email kontrollerades 2026-10-10: minsta lösenordslängd 6, inga extra teckenkrav; Secure password change och Require current password when updating avstängda. `PasswordRules` visar denna policy. Servern validerar alltid slutligt, inklusive framtida policyändringar. Om policyn ändras behöver klientens kravtext och lokala kontroll uppdateras. Återställningsbegäran visar neutral bekräftelse och spärrar upprepade klick medan anrop pågår och 60 sekunder efter svar; Supabases rate limits gäller också.

Recovery byter aldrig huvudklientens konto. Efter sparat lösenord avslutas recovery-sessionen. Användaren kan därefter välja vanlig inloggning. Pågående eller osparat pass behåller huvudsessionen tills passet sparats och användaren själv kan logga ut.

## Sessionslagring

SDK:ns tidigare SettingsSessionManager sparade serialiserad session i SharedPreferences (`se.oxelv.mintracker_preferences.xml`, projektspecifik `sb-https:--xbvifoucwpgnybtpwyrk-supabase-co-session`, med stöd för äldre `session`). Vid första starten migreras den till SecureSessionManager. Den gamla sessionen tas bort först efter lyckad krypterad skrivning. En migrationsmarkör förhindrar återimport efter utloggning. Även den äldre oprefixerade nyckeln rensas.

Det finns en enda beständig session. AES-256-GCM krypterar sessionen med en slumpad datanyckel. En AES-GCM-nyckel i Android Keystore omsluter datanyckeln. Utan biometri kan appen återställa sessionen automatiskt. När biometri aktiveras ersätts omslutningen av en Keystore-nyckel med autentisering vid varje användning och ogiltigförklaring vid ändrad biometrisk registrering. Ingen andra kopia av refresh token sparas. Lösenord och biometriska data sparas inte. Auth-preferenser undantas från molnbackup och enhetsöverföring.

AndroidX BiometricPrompt 1.1.0 använder BIOMETRIC_STRONG och CryptoObject. Svag ansiktsigenkänning och enhetens PIN ingår inte i denna modell; vanlig kontoinloggning finns som alternativ. Efter processdöd krävs CryptoObject-dekryptering. Därefter måste Supabase acceptera refresh token och returnera samma användar-ID innan appen öppnas. Felaktig/återkallad session, ogiltig nyckel eller misslyckad serverkontroll kräver lösenordsinloggning.

Efter upplåsning ligger datanyckeln kvar i processens RAM för säker tokenrotation, bakgrundssynk och GPS-tjänst. Appgränssnittet låses efter minst 60 sekunder i bakgrunden, utom medan ett träningspass pågår eller är osparat. Ingen biometrisk dialog öppnas automatiskt. Återkomst med bevarad process använder också en ny CryptoObject och serverkontroll. Detta är skydd av lokal sessionsåtkomst, inte Supabase MFA och inte ett skydd mot komprometterad Android-process.

Inställningen binds till aktuell session/användare. Manuell utloggning försöker avsluta sessionen på servern och rensar alltid lokalt även om nätet saknas. Konto B ersätter hela kontobindningen för A. Valet ”Inte nu” sparas per konto. Room-tabeller, ägarskapskontroller och sociala/träningsfunktioner är oförändrade.

## Verifiering

Automatiskt: 74 JVM-tester, inklusive lösenordsgräns/teckenkrav/bekräftelse; debug-APK och instrumenterings-APK byggs; Android Lint körs. Instrumenteringstester omfattar recovery kontra signup/magiclink, manifest-routing, krypterad sessionsåterstart och tokenrotation, utloggning/kontobyte, manipulerat ägar-ID och engångsmigrering från SDK-lagring. Dessa tester kan byggas här men kräver ansluten Android-enhet för exekvering.

`connectedDebugAndroidTest` kördes och stoppades med ”No connected devices”. Därför är nedanstående verkliga flöden ännu inte verifierade på telefon. Uteblivna enhetstester ska inte tolkas som godkända tester.

| Scenario | Status / återstående kontroll |
| --- | --- |
| 1. Skicka återställningsmejl | Supabase recovery-anrop gav HTTP 200 tidigare i arbetet; mottagning och nya appvyn behöver telefonkontroll. |
| 2. Öppna återställningslänk | Routingtest byggt; öppna riktigt mejl på telefon. |
| 3. Spara nytt lösenord | Lokala regler testade; genomför med testkonto och verifiera inloggning med nytt lösenord. |
| 4. Ogiltig/utgången/använd länk | Felhantering implementerad och routingtest byggt; testa riktiga länkar. |
| 5. Befintlig e-postverifiering | Befintligt routingtest bevarat; verifiera nytt testkonto på telefon. |
| 6. Aktivera/stäng av biometri | Kräver telefon med stark registrerad biometri. |
| 7. Lyckad biometrisk autentisering | Kräver telefon och giltig Supabase-session. |
| 8. Avbruten autentisering | Kräver telefon; vanlig lösenordsinloggning ska vara kvar. |
| 9. Enhet utan biometri | Kontrollera att aktiveringsalternativ saknas och vanlig login fungerar. |
| 10. Utgången/återkallad session | Testa accepterad tokenrotation respektive återkallad refresh token; inget lokalt genvägsinträde. |
| 11. Manuell utloggning | Lagringstest byggt; kontrollera också offline-utloggning och att biometri inte återöppnar kontot. |
| 12. Två konton | Lagringstest byggt; kontrollera A → logga ut → B samt Room, hundar och sociala vyer. |
| 13. Stäng/öppna appen | Lagringstest byggt; force-stop, återstart och 60-sekunders bakgrundslås kräver telefon. |
| 14. Start efter uppdatering | Migrations-/återstartstest byggt; installera 1.0.2 över 1.0.1 och verifiera data/session. |

## Distribution

VersionCode 3, versionName 1.0.2. Leveransen behåller befintlig debug-signatur för kompatibel uppdatering. Ingen ny permanent signeringsnyckel skapades. APK och checksumma publiceras i GitHub Releases. Versionspolicyn sätts till latest=3/minimum=1 för frivillig uppdatering i klienter med uppdateringssystemet (1.0.1 och senare). Äldre klienter utan uppdateringssystem måste installera denna APK manuellt en gång. Android kräver användarens installationsgodkännande.

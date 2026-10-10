# Socialt aktivitetsflöde – granskningsunderlag 2026-10-10

Historiskt granskningsunderlag från steg D. **Uppdaterad status:** användaren har därefter godkänt och bett om uppdatering/publicering. Migrationerna är nu tillämpade och Android-implementationen färdigställd. Se [verifieringsrapporten](social-feed-verification.md) för aktuella resultat. Texten nedan beskriver granskningsläget och besluten före godkännandet.

## Verifierade problem

1. Produktionsdatabasen saknar `public.activities.note`. Android 1.0.2 skickar och läser fältet. En normal REST-synkning med fältet nekades med HTTP 400/PGRST204; läsning av fältet nekades med 42703. Detta blockerar normal synkning och därmed möjligheten för nya lokala pass att påverka serverns progress.
2. Androids utmaningsvyer observerar inte avslutad bakgrundssynkning. Effekterna laddar vid navigation, val och manuell omladdning. Även efter lyckad serveruppdatering kan vyn därför visa gammal progress. Efter godkänd migration ska repository och vyer invalideras vid bekräftad synkning/radering och återgång till appen.
3. Den befintliga SELECT-policyn för profiler tillåter alla autentiserade användare att läsa profilrader. UI-filtrering på offentlig profil ger inget databasskydd. Förslaget lägger till en restriktiv policy: egen profil, offentlig profil eller accepterad vänskap.

## SQL att granska, i körordning

1. [008 – synkningskontrakt](../supabase/proposals/202610100008_activity_sync_contract.sql): lägger till det nullable anteckningsfält som appen redan använder och begränsar det till 500 tecken. Befintligt ägarskydd gäller fortsatt. Ingen social delning införs av denna fil.
2. [009 – socialt flöde och profilstatistik](../supabase/proposals/202610100009_social_feed_profile_stats.sql): inställningar, synlighet per aktivitet, serverberäknade utmaningshändelser, händelser, hejarop, index, triggers, RPC:er och profilpolicy. Förutsätter befintliga migrationer 001–007 samt 008. Kör filerna en gång, i ordning, först efter granskning.

Filerna ligger avsiktligt i `supabase/proposals`, så att vanliga migrationskommandon inte tillämpar dem automatiskt. De använder transaktioner och begär omladdning av PostgRESTs schema. Ingen servernyckel behövs i Android. Nya sociala tabeller kan inte läsas eller skrivas direkt av klienten; autentiserade RPC:er kontrollerar behörighet.

## Regler som förslaget inför

| Område | Regel |
|---|---|
| Automatiskt delade pass | Av som standard. Aktivering gäller nya pass som startat efter aktiveringen. Äldre privata och köade pass delas inte automatiskt. |
| Synlighet per pass | Privat som standard; användaren kan uttryckligen välja Vänner. Ändring använder versionsnummer så att gamla enheter inte skriver över nyare integritetsval. |
| Upprepad synkning | Samma aktivitets-ID ger inga dubbla händelser. Återsynkning ändrar inte befintlig synlighet; ändring sker genom separat versionskontrollerad RPC. |
| Äldre appversion | Direkt REST-synkning ger privata pass även om automatisk delning är på. Nya Android-versionen behöver den atomiska synknings-RPC:n. |
| Flödespublik | Accepterade vänner. Avslutad vänskap eller privatmarkering tar bort åtkomst vid nästa serveranrop. Redan visad information kan inte återkallas ur någons minne. |
| Flödesinnehåll | Strukturerade sammanfattningar; svenska meningar byggs i Android. Inga koordinater, rutter, start/slutpositioner, anteckningar, e-postadresser eller exakta passtider. Händelsetid avrundas till timme. Hundnamn kräver separat uttryckligt val för passet. |
| Paginering | Nyast först, normalt 20, nyckelbaserad markör på tid och ID. Servern begränsar sidstorleken till 50. |
| Hejarop | Ett hjärta per användare och händelse; idempotent lägg till/ta bort. Servern kräver aktuell läsbehörighet. |
| Personligt rekord | Längsta enskilda löppass av typen RUN, strikt längre än tidigare RUN-historik. Första passet och lika långt pass är inte rekord. Hundlöpning ingår inte i denna första rekordtyp. Källpasset måste vara delat för att rekordet ska synas. |
| Utmaningsprogress | Officiella passfilter och mått återanvänds i serverberäkningen; läsning av topplista skapar inte händelser. |
| Utmaning klar | Skapas vid serverbekräftad övergång från ej uppnått till uppnått. Högst en händelse per person och utmaning. Radering som gör målet ouppnått döljer händelsen; ny måluppfyllelse återanvänder samma ID och ursprunglig händelsetid. |
| Utmaningens publik | Privat utmaning publiceras aldrig. FRIENDS/GROUP kräver aktiverat samtycke, accepterad vänskap och att betraktaren deltar och har rätt att se utmaningen. Grupphändelse kräver också att aktören fortfarande är gruppmedlem. |
| Privat pass i utmaning | Kan påverka officiell progress. Utmaningshändelsen avslöjar endast måluppfyllelse och utmaningens titel, inga privata passvärden. Detta är ett separat samtycke genom automatisk delning. |
| Av/på igen | Avstängd automatisk delning döljer utmaningshändelser. Ny aktivering återpublicerar inte tidigare utmaningshändelser utan ny målövergång. Redan uttryckligen delade pass behåller sin synlighet. |
| Offentlig statistik | Av som standard och kräver offentlig profil. Privat profil ger ingen offentlig statistik, inte heller till vänner. Vänner kan läsa grundläggande profiluppgifter enligt profilpolicyn. |
| Statistikunderlag | Endast pass markerade Vänner. Privata pass bidrar inte till offentliga summor eller offentlig räknare för avklarade utmaningar. Därför kan offentlig statistik skilja sig från egen statistik och officiell topplista. |
| Statistikmått | Antal, distans, tid, löpning/promenad inklusive respektive hundtyp, senaste 7/30 dagar, distansviktat löptempo, längsta löppass och åtta veckor i Europe/Stockholm. Tempo beräknas från total tid/distans för giltiga löppass, inte medelvärdet av passtempon. |
| Radering | Aktivitets- och kontoradering tar bort tillhörande händelser/hejarop genom främmande nycklar; utmaningsprogress räknas om. |

## Verifiering genomförd

**Lokalt automatiskt:** `node tools/backend-tests/social-proposals.mjs` kompilerade båda förslagen i PGlite och klarade 18 testgrupper. Täcker standardvärden, anteckningskontrakt, ägarskydd, vän/icke-vän, återkallad vänskap, ändrad synlighet, inga dubletter, hejarop, riktiga rekord, statistikens opt-in och privata underlag, versionskonflikter, utmaningslivscykel, svenska aktivitetstyper, fem progressmått, paginering, automatisk delning med explicit privat undantag och kaskadradering. `node tools/backend-tests/run.mjs` klarade de 26 befintliga backendtestgrupperna. Testfixturens profilpolicy har justerats för att motsvara den inventerade produktionspolicyn före den nya restriktionen.

**Live mot befintligt schema:** `tools/backend-tests/Test-LiveChallengeProgress.ps1` använde två befintliga riktiga Supabase-testkonton via vanliga användartoken. Normal synkning med anteckningsfältet reproducerade kontraktsfelet. Med diagnostikflaggan `-DiagnosticWithoutNote` kunde serverberäkningen isoleras: 3 km/10 km gav 30 %, ytterligare 2 km gav 50 %, fel aktivitetstyp och pass utanför perioden räknades inte, radering minskade värdet. Även antal, duration och höjdmeter kontrollerades. Det andra kontot nekades åtkomst till den privata utmaningen. Tillfälliga pass och utmaningar raderades i testets cleanup. Inga lösenord eller token skrivs ut av testet.

**Begränsningar:** Det kontrollerade REST-testet är inte ett Android/Room/WorkManager-test. Sociala live-tester kan inte genomföras innan förslaget är godkänt och kört. PGlite-tester verifierar inte produktionsbelastning, verklig parallellism, nätverksavbrott eller PostgRESTs schema-cache. Androidkod har inte ändrats i detta steg; Gradle- och enhetstester för de nya funktionerna återstår efter implementation. Ingen ansluten Android-enhet finns tillgänglig i den nuvarande miljön.

## Fortsättning efter godkännande och körning

Implementera modeller och repositories med kontobunden cache, den atomiska synknings-RPC:n och versionskontrollerade integritetsändringar. Invalidera utmaningsvyer efter lyckad synkning och radering. Lägg till Aktiviteter under Socialt, paginering, hjärtan, integritetsinställningar, passens synlighetsval samt statistikvyn på besökta profiler med laddning/tomvy/felhantering. Rensa social cache vid kontobyte och återkontrollera behörighet vid återgång till vyn.

Därefter genomförs hela matrisen med två riktiga konton, inklusive offlinekö, kontobyte, hundnamn, gruppmedlemskap, integritetsåterkallelse och dubbelsynkning. Kör `:app:testDebugUnitTest`, `:app:assembleDebug` och `:app:connectedDebugAndroidTest`; det sistnämnda kräver tillgänglig enhet/emulator. APK/release ska beskriva det som faktiskt implementerats och verifierats.

# Notiscenter – migrationsförslag för godkännande

Status: migrationsförslaget godkändes och migration 010 installerades 2026-10-10. Androidgränssnitt och live-tester är färdiga. Se notification-center-verification.md för leverans och verifiering. Texten nedan beskriver det granskade förslaget.

## Inventering

- Befintlig `notifications` har stabilt UUID, `user_id`, `actor_profile_id`, aktivitet/utmaning, dedupliceringsnyckel, `is_read` och tidsstämpel. Administrativa systemmeddelanden återanvänds.
- `social_events` och `social_cheers` har stabila nycklar, serverstyrd skapandekedja och befintliga kontroller av delning, vänskap och utmaningsåtkomst.
- Produktionsschemat för `friend_requests`, `friendships`, `group_members`, `groups`, profiler och notispolicies har lästs. Vänförfrågningar har status, svarstid och förfallotid; medlemskap har `joined_at`.
- Ingen blockeringstabell hittades. Befintliga spärrade/arkiverade profiler filtreras bort.
- Den gamla aktivitetstriggern skapar notisrader även för privata pass. Förslaget tar bort denna trigger och lägger dessutom ett restriktivt synlighetsfilter på äldre notisrader för äldre appversioner.

## Val: härledd inkorg

Notiser sammanställs på servern från aktuella sociala händelser, hejarop, utmaningar, vänförfrågningar, medlemskap och befintliga systemnotiser. Det skapas inte en separat kopia av privata passuppgifter per vän.

Fördelarna är omedelbar återkallelse: pass som blir privata, borttagen vänskap, raderade pass/utmaningar och borttagna medlemskap försvinner ur både inkorg och oläst-räknare vid nästa hämtning. Stabilt UUID härleds ur källa och mottagare. WorkManager-omförsök skapar inga nya notis-ID:n. Borttaget hejarop tar bort den härledda notisen; återlagt hejarop återanvänder ID och lässtatus.

Nackdelen är läskostnaden när historiken växer. Befintliga index för vänskap, sociala aktörer, medlemskap och utmaningar återanvänds, listor använder nyckelbaserad paginering och högst 50 rader per anrop. Aktuell synlighet måste fortfarande beräknas för oläst-räknaren. Belastningsmätning och eventuell serverstyrd fanout kan behövas vid betydligt större användarantal.

## Databasändringar

- `notification_preferences`: sju kategorier och versionskontroll per konto. Systemmeddelanden kan inte stängas av via dessa val.
- `notification_receipts`: lästid per mottagare och stabilt notis-ID.
- Inga direkta klienträttigheter på de nya tabellerna. RPC:er använder autentiserat konto, kontostatus och aktuella åtkomstregler.
- RPC:er för lista, oläst antal, inställningar, markera en/alla och en begränsad passdetalj. Passdetaljen lämnar aldrig ut GPS, anteckningar eller exakta passtider till besökare.
- Befintliga administrativa meddelanden behålls. Markering som läst synkas också till gamla `notifications.is_read`.
- Gruppmedlemskap meddelas till den nya medlemmen och gruppägaren. Övriga medlemmar får inte en notis för varje anslutning.
- NPC visas som simulerat konto och måste uppfylla samma åtkomstregler, plus befintliga NPC-notisinställningar.
- Befintliga tillåtna händelser blir synliga som historik när centret introduceras. Tidigare lässtatus för gamla notiser bevaras. Härledda sociala notiser börjar olästa.

## Android efter godkänd migration

En kontobunden repository och klocka återanvänds på huvudskärmarna. En enda livscykelstyrd uppdateringsloop körs i förgrunden, med hämtning vid start, återgång, öppning och läsåtgärder. Ingen ny Realtime-infrastruktur eller Firebase-push införs.

Notiscentret får Alla/Olästa, profilbilder, fetstilt namn, relativ tid, händelseikon, blå olästmarkering, markera alla och 20 notiser per sida. Röd badge visar högst `99+`. Navigering gör serverkontroll när notisen öppnas och använder befintliga profil-, vän-, grupp- och utmaningsvyer samt en säker delad passdetalj.

En eventuell offlinecache ska vara avgränsad till konto, rensas vid utloggning och får inte användas som behörighetsbevis. Föråldrat innehåll ska märkas och mål verifieras online före öppning.

## Genomförda tester

Kommando: `node tools/backend-tests/notification-proposal.mjs`.

19 testgrupper passerar i en isolerad lokal PostgreSQL-miljö (PGlite), med befintliga migrationer 001–009 och föreslagen 010. Testerna täcker privata och delade pass, riktiga rekord, omförsök, begränsade payloads, lässtatus/antal, kontoisolering, förfalskningsförsök, hejarop, inställningar, ändrad delning, avslutad vänskap även för gamla klienter, vänförfrågningar, privata/tillåtna utmaningar, grupper, systemmeddelanden, markera alla, paginering, NPC, radering och anonym åtkomst.

Riktiga Supabase-testkonton, Androidtester, visuell kontroll, APK-bygge och publicering återstår. Dessa görs efter migrationsgodkännandet. Uppdateringen publiceras därefter direkt enligt användarens instruktion.

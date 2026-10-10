# Spezifikation: Web-App Gruppenkasse für Wohngruppen

Oct 10, 2026 · @Lukas

## Ziel und Ausgangslage

Die App macht die Gruppenkasse eines Elternvereins nachvollziehbar: Wer hat eingezahlt, wohin ist das Geld geflossen, wie viel liegt in der Barkasse.

Die Eltern einer Wohngruppe für Menschen mit Behinderung zahlen monatlich auf das Vereinskonto ein. Daraus füllen die Kassenverwalter eine Barkasse auf, aus der die häufig wechselnden Pflegekräfte Einkäufe und Ausflüge bezahlen. Heute geschieht die Dokumentation auf Papier, Belege gehen verloren, und der Kassenstand ist nur durch Zählen bekannt.

Die App soll:

- Elternbeiträge pro Familie und Monat dokumentieren
- Ausgaben der Pflegekräfte per Belegfoto erfassen und kategorisieren
- jede Bewegung der Barkasse lückenlos festhalten
- den Kassenverwaltern eine Übersicht und einen Export für die Kassenprüfung geben
- mehrere Wohngruppen getrennt verwalten können

## Rollen und Rechte

Es gibt zwei Rollen pro Wohngruppe, Kassenverwalter und Pflegekraft, dazu einen globalen Admin (siehe „Entscheidungen zur Umsetzung“); Eltern haben keinen Zugang zur App und bekommen keinen Bericht. Rechte gelten immer pro Wohngruppe.

| Funktion | Kassenverwalter | Pflegekraft |
| --- | --- | --- |
| Wohngruppe anlegen, Einstellungen ändern | ja | nein |
| Pflegekräfte freischalten, sperren | ja | nein |
| Weitere Kassenverwalter einladen | ja | nein |
| Elternbeiträge buchen | ja | nein |
| Barkasse auffüllen, Korrektur buchen | ja | nein |
| Ausgabe erfassen | ja | ja |
| Eingereichte Ausgabe ändern | nur durch Ablehnung zur Korrektur | nein |
| Ausgaben prüfen, verbuchen, ablehnen | ja | nein |
| Kontostand und Barbestand erfassen | ja | nein |
| Kassenstand sehen | ja | ja |
| Eigene Ausgaben sehen | ja | ja |
| Alle Ausgaben und Auswertungen sehen | ja | nein |
| Export für Kassenprüfung | ja | nein |

Eine Pflegekraft hat nach der Registrierung den Status „wartend“ und kann nichts tun, bis ein Kassenverwalter sie freischaltet. Ausgeschiedene Pflegekräfte werden gesperrt, nicht gelöscht, damit ihre Belege erhalten bleiben.

## Mandantenfähigkeit über Wohngruppen

Die Wohngruppe ist die oberste Einheit; alle Daten gehören genau einer Wohngruppe, und keine Gruppe sieht die Daten einer anderen.

- **Wohngruppe:** hat einen Namen (z.B. „WG Sonnenhof“), einen Beitrittscode, einen Mindestbetrag für die Kassenwarnung, genau eine Barkasse und ein Vereinskonto.
- **Anlegen:** Der erste Kassenverwalter legt die Wohngruppe an und lädt weitere Kassenverwalter per E-Mail ein.
- **Beitrittscode:** zufällig erzeugt (z.B. 8 Zeichen), vom Kassenverwalter neu erzeugbar. Pflegekräfte geben ihn bei der Registrierung ein und landen als „wartend“ in der richtigen Gruppe. Der Code ist sicherer als der Gruppenname, weil Namen erraten oder vertippt werden können.
- **Mehrere Gruppen pro Person:** Ein Kassenverwalter oder eine Springer-Pflegekraft kann mehreren Wohngruppen angehören, mit jeweils eigener Rolle. Oben in der App wechselt man per Auswahl die aktive Gruppe.
- **Trennung:** Jeder Datensatz trägt die Wohngruppen-ID; jede Abfrage filtert serverseitig danach (z.B. per Row-Level-Security in der Datenbank).
- **Kategorien:** eine globale Standardliste, die jede Gruppe um eigene Kategorien ergänzen oder ausblenden kann.

## Geldfluss und Elternbeiträge

Das Geld fließt in einer Richtung: Eltern zahlen aufs Vereinskonto, Kassenverwalter füllen die Barkasse auf, Pflegekräfte zahlen aus der Barkasse.

1. Eltern überweisen ihren Monatsbeitrag auf das Vereinskonto.
2. Der Kassenverwalter bucht den Eingang manuell in der App.
3. Der Kassenverwalter hebt Geld ab und bucht eine Auffüllung der Barkasse.
4. Pflegekräfte bezahlen bar und erfassen jede Ausgabe mit Belegfoto.

### Elternbeiträge

- Pro Familie: Name, zugehöriger Bewohner (nur Vorname oder Kürzel), monatlicher Sollbetrag, Fälligkeitstag, Kontaktadresse.
- Sollbetrag änderbar mit Gültigkeitsdatum, damit alte Monate korrekt bleiben.
- Zahlungseingang buchen: Familie, Betrag, Eingangsdatum, Monat(e), für die gezahlt wurde, optionale Notiz. Teil- und Mehrfachzahlungen möglich.
- Übersicht als Raster Familie × Monat mit Status bezahlt, teilweise, offen, überfällig.
- Optional: Erinnerungs-Mail an Familien mit überfälligem Beitrag, vom Kassenverwalter ausgelöst.

### Ausgaben direkt vom Konto

Größere Anschaffungen oder Daueraufträge, die nicht über die Barkasse laufen, bucht der Kassenverwalter als eigene Buchungsart „Kontoausgabe“ mit Beleg und Kategorie. So bleibt die Gesamtübersicht vollständig, auch ohne Bankabgleich.

## Ausgaben erfassen per Belegscan

Eine Pflegekraft erfasst eine Ausgabe in unter einer Minute: Foto machen, Vorschlag prüfen, absenden.

1. Pflegekraft tippt auf „Beleg erfassen“, die Handykamera öffnet sich (Galerie-Upload als Alternative).
2. Die App liest per OCR bzw. KI Händler, Datum und Gesamtbetrag aus und schlägt eine Kategorie vor (z.B. REWE → Lebensmittel).
3. Die Pflegekraft prüft und korrigiert die Felder und ergänzt einen kurzen Zweck („Einkauf Wochenende“).
4. Absenden: Die Ausgabe wird sofort vom Kassenstand abgezogen, Status „eingereicht“.

### Felder

- Pflicht: Belegfoto, Betrag, Datum, Kategorie
- Optional: Händler, Zweck, Bewohner (wenn die Ausgabe einer Person zugeordnet werden soll), mehrere Fotos bei langen Kassenzetteln
- Automatisch: erfasst von, erfasst am, Wohngruppe

### Belege ohne Foto

Geht ein Beleg verloren, kann die Ausgabe ohne Foto erfasst werden, aber nur mit Pflichtbegründung. Sie wird in der Prüfliste markiert.

### Status

- **eingereicht:** von der Pflegekraft erfasst, ab dann für sie nicht mehr änderbar; Fehler korrigiert sie nur, wenn der Kassenverwalter die Ausgabe ablehnt
- **geprüft:** vom Kassenverwalter bestätigt, danach nicht mehr änderbar
- **abgelehnt:** mit Kommentar zurück an die Pflegekraft, die korrigiert und neu einreicht

Der Kassenstand rechnet ab „eingereicht“, weil das Geld zu diesem Zeitpunkt die Kasse schon verlassen hat.

### Standardkategorien

Lebensmittel, Drogerie und Hygiene, Freizeit und Ausflüge, Fahrten, Haushalt, Anschaffungen, Geschenke und Feiern, Sonstiges.

## Barkasse

Die Barkasse ist ein lückenloses Kassenbuch: Jede Bewegung ist eine Buchung, der Kassenstand ergibt sich immer aus der Summe aller Buchungen.

### Buchungsarten

| Buchungsart | Richtung | Wer bucht | Pflichtangaben |
| --- | --- | --- | --- |
| Auffüllung | + | Kassenverwalter | Betrag, Datum, übergeben an (Pflegekraft oder Kasse direkt) |
| Ausgabe | − | Pflegekraft, Kassenverwalter | entsteht aus der Belegerfassung |
| Rückgabe ans Konto | − | Kassenverwalter | Betrag, Datum, Grund |
| Korrektur | + / − | Kassenverwalter | Betrag, Begründung (entsteht u.a. beim Erfassen des Ist-Bestands) |

Buchungen werden nie gelöscht. Ein Fehler wird durch eine Stornobuchung ausgeglichen, die auf die ursprüngliche Buchung verweist.

### Bestand erfassen

- Der Kassenverwalter trägt den tatsächlichen Stand des Vereinskontos und den tatsächlichen Barbestand ein.
- Die App zeigt den berechneten Stand, den eingegebenen Stand und die Differenz; bei Differenz wird eine Korrektur mit Begründung gebucht (beim allerersten Eintrag ein Anfangsbestand).
- Pflegekräfte sehen den Barbestand, erfassen aber keine Bestände.

### Warnung bei niedrigem Kassenstand

- Mindestbetrag pro Wohngruppe einstellbar, Vorschlag 50 €.
- Fällt der Stand darunter, bekommen alle Kassenverwalter der Gruppe eine Benachrichtigung (Push und E-Mail).
- Auf der Startseite der Pflegekräfte erscheint ein Hinweis, damit sie größere Einkäufe absprechen.
- Die Warnung wiederholt sich erst nach einer Auffüllung erneut, damit niemand mehrfach am Tag benachrichtigt wird.

## Auswertungen und Export

Die Startseite der Kassenverwalter beantwortet drei Fragen auf einen Blick: Wie viel ist in der Kasse, was ist zu prüfen, wer hat noch nicht gezahlt.

### Startseite Kassenverwalter

- Aktueller Kassenstand und Stand Vereinskonto (gebuchte Werte)
- Anzahl Ausgaben mit Status „eingereicht“, direkt zur Prüfliste
- Offene und überfällige Elternbeiträge
- Datum der letzten Bestandserfassung

### Startseite Pflegekraft

- Großer Button „Beleg erfassen“
- Aktueller Kassenstand, ggf. Warnhinweis
- Eigene Ausgaben der letzten 30 Tage mit Status

### Auswertungen

- Ausgaben nach Kategorie und Monat, als gestapelte Balken
- Einnahmen gegen Ausgaben pro Monat
- Verlauf des Kassenstands
- Filter nach Zeitraum, Kategorie, Pflegekraft, Bewohner

### Export

- Jahresabschluss für die Kassenprüfung als PDF: Einnahmen, Ausgaben nach Kategorie, Kassenbuch
- Alle Buchungen als CSV für Excel
- ZIP mit allen Belegfotos, Dateinamen nach Datum und Buchungsnummer

## Datenmodell

Acht Tabellen reichen; alle außer Person und der globalen Kategorieliste tragen die Wohngruppen-ID.

| Tabelle | Wichtige Felder | Beziehung |
| --- | --- | --- |
| Wohngruppe | id, name, beitrittscode, mindestbetrag\_kasse | Wurzel jedes Mandanten |
| Person | id, name, email, passwort\_hash | eine Person, viele Mitgliedschaften |
| Mitgliedschaft | person\_id, wohngruppe\_id, rolle (kassenverwalter, pflegekraft), status (wartend, aktiv, gesperrt), freigeschaltet\_von, freigeschaltet\_am | verknüpft Person und Wohngruppe |
| Familie | id, wohngruppe\_id, name, bewohner\_kuerzel, kontakt\_email | eine Familie, viele Sollbeträge und Beiträge |
| Sollbetrag | familie\_id, betrag, gueltig\_ab | Historie der Monatsbeiträge |
| Beitrag | id, familie\_id, betrag, eingangsdatum, monate\[\], notiz, gebucht\_von | Zahlungseingang aufs Konto |
| Kategorie | id, wohngruppe\_id (leer = global), name, ausgeblendet | Standardliste plus eigene |
| Buchung | id, wohngruppe\_id, konto (barkasse, vereinskonto), art (auffuellung, ausgabe, rueckgabe, korrektur, kontoausgabe, storno), betrag, datum, kategorie\_id, zweck, haendler, bewohner\_kuerzel, status, beleg\_fotos\[\], storno\_von, erfasst\_von, erfasst\_am | das zentrale Kassenbuch |

Dazu kommt als Hilfstabelle das Audit-Log (wer, wann, was, alter und neuer Wert).

## Architektur

&#91;embedded content: Architektur · 3 Container auf einem Server, 3 externe Dienste\]

Nur Caddy ist aus dem Internet erreichbar. Die App spricht nach außen nur mit OpenAI und dem Mail-Dienst, und GitHub Actions deployt per SSH.

## Tech-Stack

Spring Boot im Backend und React mit Mantine im Frontend werden zu einem einzigen Docker-Image gebaut; Frontend und API laufen unter derselben Domain.

| Bereich | Wahl | Zweck |
| --- | --- | --- |
| Backend | Spring Boot 4, Java 25 | REST-API, Geschäftslogik, liefert das gebaute Frontend aus |
| Login und Rechte | Spring Security mit Session-Cookie | kein JWT, kein CORS nötig; Rollen pro Wohngruppe aus der Mitgliedschaft |
| Datenzugriff | Spring Data JPA | Entities und Repositories |
| Mandantentrennung | Hibernate `@TenantId` + `CurrentTenantIdentifierResolver` | hängt die Wohngruppen-ID an jede Abfrage, aktive Gruppe aus der Session |
| Migrationen | Flyway | versionierte SQL-Dateien, laufen beim Start automatisch |
| Datenbank | PostgreSQL | eigener Container mit Volume |
| KI-Aufruf | Spring AI | Belegerkennung mit strukturierter Ausgabe in eine Java-Klasse |
| API-Vertrag | springdoc-openapi + orval | OpenAPI aus den Controllern, daraus typisierter TypeScript-Client |
| Benachrichtigungen | webpush-java, spring-boot-starter-mail | Web-Push ohne externen Dienst, E-Mails für Einladungen und Kassenwarnung |
| Frontend | React, Vite, TypeScript | Single-Page-App |
| Komponenten | Mantine inkl. `@mantine/charts` | mobil taugliche Formulare, Datumsauswahl, Dark Mode, Diagramme |
| Datenabruf | TanStack Query | Laden und Cachen der API-Daten |
| Formulare | Mantine Form + zod | Eingabeprüfung |
| PWA | vite-plugin-pwa | Manifest und Service Worker, installierbar auf dem Homescreen |
| Reverse Proxy | Caddy | HTTPS mit automatischen Zertifikaten |
| Tests | JUnit, Testcontainers | Tests gegen echtes Postgres, inkl. Migrationen und Mandantenfilter |

Die Kamera öffnet sich über ein Dateifeld mit `capture="environment"`. Fotos werden im Browser per Canvas auf etwa 1500 Pixel Breite verkleinert, bevor sie hochgeladen werden. Belegfotos liegen auf einem Docker-Volume und werden nur über die API mit Rechteprüfung ausgeliefert.

Angular mit PrimeNG wäre die Alternative im Frontend, falls das Team Angular besser kennt.

## Belegerkennung per KI

Das kleinste GPT-6-Modell, gpt-6-luna, reicht für normale Kassenbons und kostet deutlich unter 0,1 Cent pro Bon ([OpenAI-Preisliste](https://developers.openai.com/api/docs/pricing)).

1. Das verkleinerte Foto geht an die API; Spring AI sendet es mit einem festen JSON-Schema an gpt-6-luna.
2. Das Modell liefert Händler, Datum (ISO), Gesamtbetrag (Zahl) und einen Kategorievorschlag aus der Kategorieliste der Wohngruppe.
3. Das Backend prüft die Werte: Datum nicht in der Zukunft, Betrag größer 0 und kleiner als der Kassenstand.
4. Ist ein Feld leer oder unplausibel, geht derselbe Bon einmal an gpt-6.1-sol.
5. Die Pflegekraft sieht die Vorschläge nach 3 bis 5 Sekunden und bestätigt oder korrigiert. Schlägt der Aufruf fehl, füllt sie die Felder selbst aus.

### Kosten

| Posten | Annahme | Kosten |
| --- | --- | --- |
| Input | 1.500 bis 3.000 Tokens pro Bon, 0,10 $ pro Mio. | etwa 0,0003 $ |
| Output | 150 bis 300 Tokens pro Bon, 0,50 $ pro Mio. | etwa 0,0001 $ |
| EU-Verarbeitung | 10 % Aufschlag | vernachlässigbar |
| 100 Bons im Monat |  | wenige Cent |

Preise Stand Oktober 2026; die Tokenzahl pro Bild ist eine Schätzung.

### Vor dem Start klären

- [ ] Unterstützt gpt-6-luna Bilder als Eingabe (Modellseite bei OpenAI)?
- [ ] Test mit 20 bis 30 echten Bons aus der Wohngruppe, inklusive verblasster und langer Bons
- [ ] Auftragsverarbeitungsvertrag mit OpenAI, EU-Datenresidenz aktiviert

## Hosting, Deployment und Betrieb

Die App läuft als ein Docker-Compose-Stack auf einem Strato Linux-VPS mit 1 vCore und 2 GB RAM, Standort Deutschland; ein Push auf `main` deployt automatisch. Grundsatz: Build, Deployment und Updates laufen automatisch und sind im Repository beschrieben; von Hand passieren nur Serverbestellung, DNS-Eintrag und das Hinterlegen der Geheimnisse.

### Container

| Container | Inhalt | Speicher |
| --- | --- | --- |
| caddy | Reverse Proxy, HTTPS, Weiterleitung an `app:8080` | Volume `/data` für Zertifikate |
| app | Spring Boot JAR mit eingebautem React-Frontend, JVM mit `-Xmx512m` | Volume für Belegfotos |
| db | PostgreSQL | Volume für Daten |

### Build

Ein mehrstufiges Dockerfile baut zuerst das Frontend mit Node, legt es nach `src/main/resources/static`, baut dann das JAR mit Maven und packt es in ein schlankes JRE-Image.

### Ressourcen auf dem kleinen Server

1 vCore und 2 GB RAM reichen bei wenigen gleichzeitigen Nutzern; der Stack braucht etwa 1 bis 1,1 GB und lässt knapp 1 GB Reserve.

| Komponente | Einstellung | Bedarf etwa |
| --- | --- | --- |
| Betriebssystem und Docker | Swap-Datei 1 GB als Puffer | 200 MB |
| app | `-Xmx512m -XX:+UseSerialGC`, Hikari-Pool 5 Verbindungen | 600 bis 700 MB |
| db | `shared_buffers=128MB`, `max_connections=20` | 150 bis 200 MB |
| caddy | Standard | 30 MB |

- Images werden nur in GitHub Actions gebaut, nie auf dem Server; nach jedem Deploy löscht `docker image prune -f` alte Images.
- Belegfotos werden im Browser als WebP mit etwa 150 bis 300 KB gespeichert, bei 100 Bons im Monat rund 360 MB pro Jahr.
- Wird die SSD knapp, hilft ebenfalls ein größerer Tarif.
- Mit einem Kern startet Spring Boot in 20 bis 40 Sekunden; Deploys laufen deshalb abends.
- Reicht der Server nicht mehr, ist der nächste Schritt ein manueller Tarifwechsel bei Strato.

### Automatisierung

Ein neuer Server ist mit einem Befehl in unter 30 Minuten wieder betriebsbereit; nichts hängt an Wissen, das nur im Kopf einer Person liegt.

| Bereich | Automatisiert durch | Auslöser |
| --- | --- | --- |
| Server-Einrichtung | Setup-Skript setup.sh im Repository | einmalig per SSH ausgeführt |
| Konfiguration (Compose-Datei, Caddyfile, Postgres-Einstellungen) | im Repository, vom Deploy auf den Server kopiert | jeder Deploy |
| Geheimnisse | GitHub Secrets, Pipeline schreibt die `.env` | jeder Deploy |
| Build und Tests | GitHub Actions mit Testcontainers | jeder Push |
| Deployment | GitHub Actions per SSH | Push auf `main` |
| Datenbank-Migrationen | Flyway beim Start der App | jeder Deploy |
| Rollback | Pipeline bei fehlgeschlagenem Health-Check, manuell per Workflow-Knopf | automatisch oder auf Klick |
| HTTPS-Zertifikate | Caddy | laufend |
| Aufräumen | `docker image prune -f` nach dem Deploy | jeder Deploy |
| Sicherheitsupdates des Betriebssystems | unattended-upgrades | täglich |
| Abhängigkeiten (Maven, npm, Docker-Images) | Renovate oder Dependabot als Pull Requests | wöchentlich |
| Überwachung | Uptime-Dienst auf `/actuator/health`, Mail bei Ausfall | laufend |

### Server-Einrichtung per Skript

- Docker und Docker Compose installieren
- Firewall: nur Ports 22, 80 und 443 offen
- SSH nur per Schlüssel, kein Root-Login per Passwort
- Swap-Datei 1 GB anlegen
- unattended-upgrades aktivieren
- Deploy-Benutzer mit eingeschränkten Rechten anlegen

Von Hand bleiben nur: Server bestellen, A-Record der Domain auf die Server-IP setzen, SSH-Schlüssel und Geheimnisse in GitHub hinterlegen.

### Deployment bei jedem Push auf `main`

1. GitHub Actions führt die Tests mit Testcontainers aus.
2. Das Image wird gebaut und mit dem Git-Hash als Tag in der GitHub Container Registry abgelegt.
3. Die Pipeline kopiert Compose-Datei und Caddyfile auf den Server, schreibt die `.env` aus den GitHub Secrets und führt `docker compose pull && docker compose up -d` aus.
4. Spring Boot startet in 20 bis 40 Sekunden neu, Flyway spielt neue Migrationen ein.
5. Die Pipeline ruft `/actuator/health` auf. Antwortet die App nicht innerhalb von 2 Minuten, setzt sie automatisch den vorigen Image-Tag und startet neu.

Ein manueller Rollback auf eine beliebige frühere Version läuft über einen eigenen Workflow mit Knopf in GitHub, ohne SSH-Zugriff von Hand. Optional deployt derselbe Workflow ein Staging unter einer Subdomain auf demselben Server.

Achtung bei Migrationen: Ein automatischer Rollback setzt nur die App zurück, nicht die Datenbank. Migrationen werden deshalb abwärtskompatibel geschrieben, also erst Spalten hinzufügen und erst in einer späteren Version entfernen.

### Caddy und Zertifikate

Caddy holt das HTTPS-Zertifikat über Let's Encrypt (ACME) automatisch und erneuert es im Hintergrund, wenn etwa ein Drittel der Laufzeit übrig ist; notfalls weicht er auf ZeroSSL aus. Die ganze Konfiguration:

```
kasse.deinverein.de {
    reverse_proxy app:8080
    encode gzip
    request_body {
        max_size 20MB
    }
}
```

- Das Volume `/data` muss erhalten bleiben, sonst holt Caddy bei jedem Neuaufbau ein neues Zertifikat und stößt an die Rate-Limits von Let's Encrypt.
- Beim ersten Ausprobieren die Staging-Umgebung von Let's Encrypt eintragen und danach wieder entfernen.
- Fehlersuche: `docker compose logs caddy`.

### Überwachung

Ein kostenloser Uptime-Dienst ruft `/actuator/health` auf und meldet Ausfälle per E-Mail.

### Laufende Kosten

| Posten | pro Monat |
| --- | --- |
| Strato Linux-VPS, 1 vCore, 2 GB RAM | laut Strato-Bestellung |
| Belegerkennung (100 Bons) | wenige Cent |
| Domain | wenige Euro pro Jahr |
| Strato-Backup-Option (optional, nicht im Tarif enthalten) | Aufpreis laut Strato-Shop |

Preise Stand Oktober 2026.

## Datenschutz und Aufbewahrung

Alle Daten liegen auf einem Server in Deutschland; personenbezogene Daten werden so sparsam wie möglich gespeichert.

### Datenschutz (DSGVO)

- Bewohnerdaten minimal halten: nur Vorname oder Kürzel, keine Gesundheitsdaten
- Auf Belegfotos können sensible Inhalte stehen (z.B. Apotheke); Zugriff nur für Kassenverwalter und die erfassende Pflegekraft
- Auftragsverarbeitungsvertrag mit Hosting- und OCR-Anbieter
- Datenschutzhinweis bei der Registrierung, Einwilligung der Pflegekräfte dokumentiert
- Gesperrte Personen bleiben als Namen an ihren Buchungen sichtbar, verlieren aber jeden Zugriff

### Aufbewahrung und Nachvollziehbarkeit

- Buchungen nie löschen, nur stornieren; jede Änderung im Audit-Log
- Belege und Buchungen in der Regel 10 Jahre aufbewahren; der Kassenprüfer oder Steuerberater des Vereins sollte die Frist bestätigen

## Entscheidungen zur Umsetzung (10.10.2026)

Diese Entscheidungen ergänzen die Spezifikation und haben bei Widersprüchen Vorrang vor den Abschnitten oben.

### Rollen und Zugang

- **Admin:** globale Rolle (Feld `ist_admin` an Person), sieht und darf alles in allen Wohngruppen. Nur der Admin legt Wohngruppen an und ernennt Kassenverwalter. Der erste Admin wird initial per SQL direkt in der Datenbank gesetzt.
- **Keine offene Registrierung für Wohngruppen.** Kassenverwalter werden vom Admin per Einladungslink (Token, 7 Tage gültig, wird dem Admin angezeigt und von ihm weitergegeben) angelegt; hat die Person schon ein Konto, wird nur die Mitgliedschaft ergänzt. Kassenverwalter laden keine weiteren Kassenverwalter ein.
- Pflegekräfte registrieren sich weiterhin selbst mit Beitrittscode und landen als „wartend“.
- Passwort-Reset ohne E-Mail: Ein Kassenverwalter (für Pflegekräfte) bzw. der Admin erzeugt einen einmaligen Link (24 Stunden gültig) und gibt ihn persönlich weiter.

### Geld und Buchungen

- **Anfangsbestand:** zusätzliche Buchungsart `anfangsbestand`, je einmal für Barkasse und Vereinskonto, vom Kassenverwalter gebucht.
- **Auffüllung und Rückgabe** werden als zwei verknüpfte Buchungen gespeichert (Vereinskonto − / Barkasse + bzw. umgekehrt).
- **Vereinskonto-Stand** wird berechnet: Anfangsbestand + Beiträge − Auffüllungen + Rückgaben − Kontoausgaben.
- **Zahlung für mehrere Monate:** wird auf den ältesten offenen Monat zuerst verteilt, ein Überschuss gilt als Guthaben für Folgemonate.
- **Abgelehnte Ausgaben** bleiben im Kassenstand abgezogen, bis sie korrigiert neu eingereicht oder vom Kassenverwalter storniert werden.
- **Negativer Kassenstand** ist erlaubt, wird aber als Warnung angezeigt. Die Prüfung „Betrag kleiner als Kassenstand“ gilt nur für die Plausibilisierung des KI-Vorschlags.
- **Kein Kassensturz.** Stattdessen erfasst der Kassenverwalter jederzeit den Ist-Stand von Vereinskonto und Barkasse (siehe „Bestand erfassen“); Differenzen werden als Korrektur gebucht. Keine Zählung durch zwei Personen, kein Intervall, keine Erinnerung.

### Datenmodell-Ergänzungen

- Globale Kategorien in eigener Tabelle ohne Mandantenfilter; pro Gruppe eine Tabelle für eigene Kategorien und das Ausblenden globaler Kategorien.
- Familie: `faelligkeitstag`
- Buchung: `begruendung_ohne_beleg`, `ablehnungs_kommentar`, `geprueft_von`, `geprueft_am`, `verknuepft_mit` (Gegenbuchung)
- Person: `ist_admin`
- Neue Tabellen: Einladung (Token, E-Mail, Wohngruppe, Rolle, gültig bis), Passwort-Reset-Token

### Kategorien, Unterkategorien und Belegpositionen (10.10.2026)

- Kategorien haben **eine** Ebene Unterkategorien. Standardmäßig hat nur **Lebensmittel** Unterkategorien: Obst und Gemüse · Brot und Backwaren · Milch, Käse und Eier · Fleisch, Wurst und Fisch · Tiefkühl und Fertiggerichte · Grundnahrungsmittel · Getränke · Süßes und Snacks · Aufstriche und Frühstück · Sonstige Lebensmittel. Kein Pfand.
- Ein Beleg besteht aus **Positionen** (Kategorie + Betrag). Jede Position gehört genau einer Kategorie; gewählt werden kann nur eine Kategorie ohne Unterkategorien (also z. B. „Obst und Gemüse“ oder „Haushalt“, nicht „Lebensmittel“). Auf einem Beleg können Unterkategorien und andere Hauptkategorien gemischt vorkommen.
- Der Betrag der Ausgabe ist die **Summe der Positionen**; es gibt keinen getrennten Gesamtbetrag und damit keinen Restbetrag. Gleiche Kategorien auf einem Beleg werden zusammengefasst, jede Position muss größer als 0 sein.
- Die Belegerkennung liefert die Aufteilung als Vorschlag (Summe je Kategorie). Passt die Summe nicht zur Endsumme laut Bon oder fehlt eine Kategorie, wird einmal das stärkere Modell gefragt. Die Pflegekraft prüft und korrigiert die Aufteilung.
- Kategorien verwalten (ein-/ausblenden, eigene hinzufügen, optional als Unterkategorie) darf nur der Kassenverwalter, nicht die Pflegekraft. Wird eine Hauptkategorie ausgeblendet, sind auch ihre Unterkategorien nicht mehr wählbar.
- Auswertungen, PDF und CSV arbeiten mit den Positionen; Auswertungen lassen sich nach Haupt- oder Unterkategorie darstellen. Ein Kategoriefilter auf eine Hauptkategorie schließt ihre Unterkategorien ein.
- Neue Tabelle `buchung_position`; Spalte `parent_id` an den Kategorietabellen. Bestehende Ausgaben bekommen bei der Migration eine Position über den vollen Betrag.

### Technische Festlegungen

- Modellnamen für die Belegerkennung sind Konfiguration; die KI-Erkennung ist abschaltbar (dann manuelle Eingabe).
- **Keine E-Mails und keine Push-Benachrichtigungen** (nachträglich entfernt, 10.10.2026): Die App verschickt nichts. Die Warnung bei niedrigem Kassenstand erscheint nur als Hinweis in der App (Startseite von Kassenverwalter und Pflegekraft). Es gibt keine Erinnerungs-Mails an Familien; die Kontaktadresse der Familie ist nur ein Datenfeld.
- Domain aus der Konfiguration; `kasse.deinverein.de` ist ein Platzhalter.
- Belegfotos als WebP, wenn der Browser es kann, sonst JPEG.
- PDF-Export mit OpenPDF.
- Oberfläche nur auf Deutsch.
- Nicht in Version 1: Staging-Umgebung, E-Mail- und Push-Benachrichtigungen.

## Nicht im Umfang und offene Fragen

### Bewusst nicht in Version 1

- Bankabgleich (CSV-Import oder Banking-Schnittstelle)
- Erstattung privater Auslagen an Pflegekräfte; bezahlt wird ausschließlich aus der Barkasse
- Gruppenkarten und andere bargeldlose Zahlungswege
- Verwaltung der Barbeträge der Bewohner selbst; diese bleiben strikt von der Elternkasse getrennt

Backups sind in Version 1 bewusst nicht vorgesehen. Fällt der Server aus oder wird die Festplatte beschädigt, sind Buchungen und Belegfotos verloren; die einfachste spätere Absicherung ist die zubuchbare Backup-Option von Strato.

### Offene Fragen

- [ ] Wer bezahlt den Betrieb (wenige Euro im Monat), der Verein oder der Träger?
- [ ] Welche Aufbewahrungsfrist bestätigt der Kassenprüfer?

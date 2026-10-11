# Observability: Design

Stand: 11.10.2026. Ziel: Die Gruppenkasse lässt sich technisch und fachlich überwachen, ohne dass personenbezogene Daten in Metriken oder Logs landen. Alles läuft selbst gehostet auf derselben VM (4 GB RAM) wie die Anwendung.

## Entscheidungen

| Thema | Entscheidung |
| --- | --- |
| Hosting | Selbst gehostet auf der Anwendungs-VM (4 GB RAM), kein Cloud-Dienst |
| Stack | Micrometer + Prometheus, Loki + Alloy für Logs, Grafana für Dashboards und Alarmregeln |
| Zugriff | Grafana unter `grafana.<Domain>` über Caddy mit HTTPS, Anmeldung Pflicht, keine Selbstregistrierung. Alle anderen Dienste nur im internen Docker-Netz |
| Alarme | Regeln werden definiert und in Grafana angezeigt, es wird **nichts versendet** (kein Signal, keine E-Mail, kein Push). Die App hat bewusst keine Benachrichtigungen |
| Tracing | Nicht enthalten (eine einzelne Anwendung) |
| Lokal | Compose-Profil `monitoring` startet denselben Stack auf `localhost:3000` |

Nicht im Umfang: Tracing, Langzeitspeicher, Überwachung von außerhalb der VM (dafür bleibt ein externer Uptime-Check auf `/actuator/health`, siehe `docs/betrieb.md`), Backups.

## Architektur

```
Browser -> Caddy -> app:8080 (API + Oberfläche)
                 -> grafana:3000 (grafana.<Domain>)

app:8081 (Management, nur intern) <- Prometheus (scrape alle 15 s)
postgres_exporter, node_exporter, cAdvisor, blackbox_exporter, Caddy-Metriken <- Prometheus
Docker-Logs -> Alloy -> Loki
Prometheus, Loki -> Grafana (Dashboards, Alarmregeln)
```

Speicherlimits je Container (Richtwerte): Prometheus 300 MB, Loki 300 MB, Grafana 250 MB, Alloy 150 MB, cAdvisor 120 MB, übrige Exporter je 30 MB. Das sind rund 1,3 GB zusätzlich zu App, Datenbank und Caddy (rund 1,1 GB). Aufbewahrung: Prometheus 30 Tage (Größenlimit 3 GB), Loki 14 Tage (Größenlimit 3 GB).

## Anwendung (Spring)

1. **Abhängigkeit** `micrometer-registry-prometheus`. Exponiert werden `health`, `info`, `prometheus`.
2. **Management-Port 8081** (`management.server.port`). Er wird in Docker nicht veröffentlicht und nicht von Caddy durchgereicht. Folgen: Dockerfile-HEALTHCHECK, `deploy.sh` (`healthy`), E2E-Stack und `docker-compose.yml` (lokal) zeigen auf 8081. Die öffentliche Adresse `/actuator/health` bleibt über eine Caddy-Route auf `app:8081` für den externen Uptime-Check erhalten, nur `health` (keine anderen Actuator-Pfade). `SecurityConfig` und `SpaConfig` passen darauf an.
3. **Strukturierte Logs:** `logging.structured.format.console=logstash` in Produktion (per Umgebungsvariable umschaltbar, lokal bleibt Klartext). Ein Filter vergibt je Anfrage eine Request-ID (MDC `requestId`, auch als Antwort-Header `X-Request-Id`) und setzt `wohngruppeId` aus dem Mandantenkontext. Keine Namen, E-Mails, Beträge oder Tokens in Logzeilen.
4. **Technische Metriken** kommen aus Spring Boot: JVM, GC, Threads, Tomcat, HikariCP, HTTP-Server mit Histogrammen (Route als Template, nie die konkrete URL), Logback-Zähler, Flyway, `@Scheduled`-Läufe. Perzentil-Histogramme für `http.server.requests` und die Belegerkennung aktivieren.
5. **Fachliche Metriken** (Präfix `gk_`, Labels nur mit begrenzter Kardinalität, keine Personen, keine Gruppen, keine Beträge):

| Metrik | Art | Labels |
| --- | --- | --- |
| `gk_ledger_bookings_total` | Zähler | `type` (auffuellung, rueckgabe, ausgabe, beitrag, storno, bestand) |
| `gk_expense_transitions_total` | Zähler | `from`, `to` |
| `gk_login_total` | Zähler | `outcome` (success, failure, locked) |
| `gk_registrations_total`, `gk_invitations_total` | Zähler | `outcome` |
| `gk_upload_rejected_total` | Zähler | `reason` |
| `gk_receipt_ai_duration_seconds` | Timer | `model`, `outcome` (ok, uncertain, implausible, error, timeout) |
| `gk_receipt_ai_fallback_total`, `gk_receipt_ai_gave_up_total` | Zähler | `reason` |
| `gk_receipt_end_to_end_seconds` | Timer | `outcome`; Zeit von der Aufnahme bis zum Ergebnis (EINGEREICHT oder MANUELL) |

   Es gibt keine Momentaufnahmen aus der Datenbank (keine Gauges für offene Belege, Beiträge oder Mitglieder) und kein Label mit Gruppennamen. Token-Verbrauch liefert Spring AI selbst (`gen_ai.client.token.usage`).
6. Alle Zähler entstehen an den bestehenden Stellen (`LedgerService`, `ExpenseService`, Auth, `ReceiptProcessor`, `ReceiptRecognitionService`), ohne die Geschäftslogik zu ändern.

## Monitoring-Stack

Dateien unter `deploy/monitoring/`:

- `docker-compose.monitoring.yml` (wird mit der Haupt-Compose-Datei zusammen gestartet), `prometheus/prometheus.yml`, `prometheus/rules/*.yml`, `loki/loki.yml`, `alloy/config.alloy`, `blackbox/blackbox.yml`.
- `grafana/provisioning/{datasources,dashboards,alerting}/*.yml` und `grafana/dashboards/*.json`.
- Grafana: `GF_SECURITY_ADMIN_PASSWORD` aus `GRAFANA_ADMIN_PASSWORD` (neues Secret), `GF_USERS_ALLOW_SIGN_UP=false`, anonymer Zugriff aus, Cookies `secure`, `GF_SERVER_ROOT_URL=https://grafana.<Domain>`.
- Alloy liest nur die Logs der Container `app`, `caddy` und `db` (Docker-Socket nur lesend), parst das JSON der App und setzt die Labels `container` und `level`. Keine hochkardinalen Labels (Request-ID bleibt in der Zeile).
- Caddy: zusätzlicher Block `grafana.{$DOMAIN}` mit `reverse_proxy grafana:3000`; Caddy-Metriken über den Admin-Endpunkt nur intern. Neue Variable `GRAFANA_DOMAIN`, neuer DNS-A-Record.
- `deploy.sh` und die Pipeline kopieren den Ordner `deploy/monitoring/` mit. Neue Secrets: `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_DOMAIN`.

## Dashboards

Als JSON im Repo, automatisch eingespielt, in zwei Ordnern.

- **Technik:** Übersicht (Ampeln, aktive Alarme), Anwendung (Rate, Fehler, p50/95/99 pro Route, JVM, GC, Threads, Tomcat, Hikari), Datenbank (Verbindungen, Sperren, Größe, langsame Abfragen laut Log), Server und Container (CPU, RAM, Swap, Platte, Neustarts), Logs (Fehler, Suche nach Request-ID).
- **Fachlich:** Belegerkennung (Trichter: erfasst, gelesen, unsicher, manuell; Dauer je Modell; Fallback- und Fehlerquote; Token-Verbrauch; Gesamtdauer), Betrieb (Buchungen und Beleg-Statuswechsel als Aktivität), Sicherheit (Logins, 401/403/429, gesperrte Konten).

## Alarmregeln

Grafana-eigene Alarmierung, als Dateien im Repo. Zustand sichtbar in Grafana und auf der Übersicht, keine Zustellung.

| Bereich | Regel |
| --- | --- |
| Verfügbarkeit | App nicht erreichbar (2 min), HTTPS-Check fehlgeschlagen (2 min), Zertifikat läuft in weniger als 14 Tagen ab |
| Anwendung | 5xx-Rate über 2 % (5 min), p95 über 2 s (10 min), Heap über 85 % (10 min), Verbindungspool wartet (5 min) |
| Server | Platte über 85 %, verfügbarer RAM unter 10 %, Container-Neustart |
| Belegerkennung | Fehlerquote der KI über 50 % (15 min), mehr als 5 Belege aufgegeben pro Stunde |
| Fachlich | Mehr als 10 fehlgeschlagene Logins pro Minute |

## Datenschutz

Metriken und Logs enthalten keine Personendaten. Es gibt keine Metrik je Wohngruppe und keinen Gruppennamen in Labels. **Geldbeträge (Kassenstände, Buchungsbeträge, Ausgaben) werden nie als Metrik oder Log ausgegeben**, auch nicht aggregiert; die Metriken zählen nur Vorgänge. Der Datenschutzhinweis und `docs/betrieb.md` werden um den Satz ergänzt, dass technische und aggregierte Betriebsdaten 14 bzw. 30 Tage aufbewahrt werden.

## Tests

- **Backend** (Integrationstests): Login-Zähler und Buchungszähler steigen, Belegerkennungs-Timer tragen Modell und Ergebnis, es gibt keine Metrik mit Geldbeträgen oder Gruppennamen, `/actuator/prometheus` ist über Port 8080 nicht erreichbar, Logzeilen enthalten keine E-Mail-Adresse und keine Passwörter.
- **E2E:** Im Teststack ist der Management-Port erreichbar. Nach einem Beleg-Durchlauf müssen `gk_receipt_ai_duration_seconds_count` und `gk_ledger_bookings_total` gestiegen sein, und `/actuator/prometheus` darf über den öffentlichen Port nicht antworten.
- **CI:** `docker compose config` für beide Compose-Dateien, `promtool check config` und `promtool check rules`, Gültigkeit der Dashboard-JSONs (`jq`).
- **Lokal:** `docker compose --profile monitoring up` zeigt Dashboards mit Daten aus einem Beispiel-Durchlauf.

## Reihenfolge der Umsetzung

1. Anwendung: Abhängigkeit, Management-Port, Logs, Metriken, Tests.
2. Stack: Compose, Prometheus, Loki, Alloy, Exporter, Grafana-Provisioning, lokal startbar.
3. Dashboards und Alarmregeln.
4. Deployment: Caddy, `deploy.sh`, Pipeline, Doku, Datenschutztext.

## Offene Risiken

- `cAdvisor` und `node_exporter` brauchen erhöhte Rechte auf dem Host (Lesezugriff auf `/proc`, `/sys`, Docker-Socket). Mounts nur lesend.
- Ohne Zustellung sieht niemand einen Alarm, der nicht in Grafana nachgesehen wird. Der externe Uptime-Check bleibt deshalb die einzige aktive Meldung bei Ausfall.
- Fällt die VM aus, fällt auch das Monitoring aus.

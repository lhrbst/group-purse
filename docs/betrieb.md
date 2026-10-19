# Betrieb der Gruppenkasse

## Einmalig von Hand

1. **Server bestellen** (Strato Linux-VPS, 1 vCore, 2 GB RAM, Standort Deutschland) und Root-Zugang notieren.
2. **DNS:** A-Record der Domain (z. B. `kasse.deinverein.de`) auf die Server-IP setzen.
3. **SSH-Schlüssel für den Deploy** erzeugen (nur für diesen Zweck):
   `ssh-keygen -t ed25519 -f deploy_key -C deploy@github -N ""`
4. **Server einrichten** (Docker, Firewall 22/80/443, SSH nur per Schlüssel, Swap, Sicherheitsupdates, Deploy-Benutzer):
   `ssh root@SERVER 'bash -s -- "'"$(cat deploy_key.pub)"'"' < deploy/setup.sh`
5. **GitHub-Repository** anlegen, Code pushen und unter *Settings → Secrets and variables → Actions* hinterlegen:

| Secret | Inhalt |
| --- | --- |
| `SSH_HOST` | IP oder Hostname des Servers |
| `SSH_USER` | `deploy` |
| `SSH_KEY` | Inhalt der Datei `deploy_key` (privater Schlüssel) |
| `DOMAIN` | z. B. `kasse.deinverein.de` |
| `DB_PASSWORD` | langes Zufallspasswort (`openssl rand -base64 32`) |
| `OPENAI_API_KEY` | API-Schlüssel (optional; leer lassen, wenn die Belegerkennung aus ist) |
| `RECEIPT_AI_ENABLED` | `true` oder `false` (optional, Standard `false`) |
| `RECEIPT_MODEL`, `RECEIPT_FALLBACK_MODEL` | Modellnamen (optional), Standard `gpt-6-luna` / `gpt-6.1-sol` |
| `GRAFANA_DOMAIN` | Subdomain für Grafana, z. B. `grafana.kasse.deinverein.de` (eigener DNS-A-Record auf dieselbe IP) |
| `GRAFANA_ADMIN_PASSWORD` | langes Zufallspasswort für den Grafana-Admin (`openssl rand -base64 24`) |

   Pflicht sind `SSH_HOST`, `SSH_USER`, `SSH_KEY`, `DOMAIN`, `DB_PASSWORD`, `GRAFANA_DOMAIN` und `GRAFANA_ADMIN_PASSWORD`. Fehlt eines, bricht der Deploy-Job ab,
   bevor er etwas auf dem Server ändert (sonst würde `secrets.env` leer überschrieben). Die übrigen sind optional.

6. **Push auf `main`** startet die Pipeline: Tests, Image bauen, auf den Server kopieren und starten, danach von außen prüfen.
   Läuft schon ein manuell eingerichteter Server, siehe den nächsten Abschnitt.
7. **Ersten Admin anlegen:** Die Seite `/registrieren` geht nur mit Beitrittscode. Deshalb den ersten Admin einmal direkt in der Datenbank anlegen.
   Das Passwort wird dabei mit `pgcrypto` gehasht (kompatibel mit dem BCrypt der App):

```sql
-- auf dem Server: docker compose -f /opt/gruppenkasse/docker-compose.yml exec db psql -U grouppurse grouppurse
create extension if not exists pgcrypto;
insert into person (id, name, email, passwort_hash, ist_admin, datenschutz_zugestimmt_am)
values (gen_random_uuid(), 'Dein Name', 'du@deinverein.de', crypt('DEIN-LANGES-PASSWORT', gen_salt('bf', 10)), true, now());
```

   Hat die Person schon ein Konto, genügt: `update person set ist_admin = true where email = 'du@deinverein.de';`
   Danach legt der Admin unter *Wohngruppen* die Gruppen an und erzeugt für jeden Kassenverwalter einen Einladungslink und gibt ihn persönlich weiter.

## Umstieg: vom manuellen Deploy auf die Pipeline

Für einen Server, der bisher von Hand (Web-Konsole, `./deploy.sh latest`) betrieben wurde. Die Pipeline loggt sich als Benutzer `deploy` ein, schreibt `secrets.env`
neu und startet den Stack. Deshalb müssen Benutzer, Dateirechte und Secrets vorher stimmen.

1. **Schlüsselpaar für die Pipeline erzeugen** (auf dem eigenen Rechner, in einem Ordner **außerhalb des Repos**, z. B. Dokumente):
   - PowerShell: `ssh-keygen -t ed25519 -f deploy_key -C deploy@github -N '""'`
   - Git Bash oder Linux/macOS: `ssh-keygen -t ed25519 -f deploy_key -C deploy@github -N ""`

   Es entstehen `deploy_key` (privat, nie weitergeben oder committen) und `deploy_key.pub` (öffentlich).
2. **Auf der VM (Web-Konsole, als root) den Deploy-Benutzer einrichten.** Das Skript ist idempotent; es legt den Benutzer `deploy` an, trägt den öffentlichen Schlüssel ein,
   richtet die Firewall (22, 80, 443) ein und schaltet die SSH-Passwortanmeldung ab (die Web-Konsole ist davon nicht betroffen). Den Inhalt von `deploy_key.pub` einsetzen:

   ```
   curl -fsSL https://raw.githubusercontent.com/lhrbst/group-purse/main/deploy/setup.sh -o /tmp/setup.sh
   bash /tmp/setup.sh "ssh-ed25519 AAAA...hier-den-ganzen-Inhalt-von-deploy_key.pub... deploy@github"
   ```
3. **Dateien dem Benutzer `deploy` geben.** Bisher gehört alles in `/opt/gruppenkasse` root; die Pipeline kopiert dort als `deploy` hinein.

   ```
   chown -R deploy:deploy /opt/gruppenkasse
   ls -ld /opt/gruppenkasse
   ```
4. **Aktuelle Werte ablesen.** Die Secrets in GitHub müssen den Werten der laufenden Installation entsprechen:

   ```
   cat /opt/gruppenkasse/secrets.env
   ```

   Die Werte stehen in einfachen Anführungszeichen; ohne die Anführungszeichen kopieren. **`DB_PASSWORD` muss exakt übereinstimmen:** Das Passwort steckt schon im Datenbank-Volume,
   ein anderes würde der App den Datenbankzugriff nehmen. Danach die Konsole leeren (`clear`), damit die Werte nicht im Verlauf stehen.
5. **GitHub-Secrets anlegen:** Repository → *Settings → Secrets and variables → Actions → New repository secret*, je ein Secret aus der Tabelle oben.
   - `SSH_HOST`: IP der VM, `SSH_USER`: `deploy`.
   - `SSH_KEY`: der **gesamte** Inhalt von `deploy_key` einschließlich der Zeilen `-----BEGIN OPENSSH PRIVATE KEY-----` und `-----END OPENSSH PRIVATE KEY-----`.
   - Alle anderen aus der `secrets.env` der VM (Schritt 4).
6. **Schlüssel testen** (auf dem eigenen Rechner, im Ordner mit `deploy_key`): `ssh -i deploy_key deploy@VM-IP "docker ps"` muss die laufenden Container zeigen.
   Danach `deploy_key` und `deploy_key.pub` löschen (der private Schlüssel liegt jetzt nur noch in GitHub; geht er verloren, einfach ein neues Paar erzeugen und die Schritte 1, 2 und 5 für `SSH_KEY` wiederholen).
7. **Dann auf `main` pushen** (oder dem Assistenten Bescheid geben) und unter *Actions* zusehen. Erwartet: `test`, `monitoring-config`, `docker-build`, `deploy` grün.
   Der Deploy-Job endet mit „ist erreichbar und gesund“.

**Wenn etwas schiefgeht**
- *„Fehlende GitHub-Secrets“*: die genannten Secrets ergänzen und den Lauf erneut starten (*Re-run*). Auf dem Server wurde nichts verändert.
- *„Permission denied“ beim Kopieren*: Schritt 3 (`chown`) fehlt oder die VM-Datei gehört wieder root.
- *Health-Check schlägt fehl*: `deploy.sh` setzt automatisch auf den vorigen Tag zurück. Meist ist ein Secret falsch, vor allem `DB_PASSWORD`. Logs: `docker compose logs --tail=80 app` im Ordner `/opt/gruppenkasse`.
- *„Von außen prüfen“ schlägt fehl, App läuft aber*: DNS-Eintrag, Firewall oder Caddy-Zertifikat prüfen (`docker compose logs caddy`).
- *Schnell zurück zu einer älteren Version:* *Actions → Rollback → Run workflow* und den Git-Hash eintragen.

## Alltag

- **Deploys** laufen bei jedem Push auf `main` automatisch (nie bei Pull Requests) und enden mit einer Prüfung von außen. Spring Boot braucht auf einem Kern 20 bis 40 Sekunden zum Start, deshalb bevorzugt abends deployen.
- **Rollback:** Schlägt der Health-Check (`/actuator/health`, 2 Minuten) fehl, setzt die Pipeline automatisch den vorigen Image-Tag.
  Manuell: GitHub → *Actions → Rollback* → *Run workflow* → gewünschten Tag (Git-Hash) eintragen.
- **Datenbank-Migrationen** (Flyway) laufen beim Start. Ein Rollback setzt nur die App zurück, nicht die Datenbank. Migrationen deshalb abwärtskompatibel schreiben
  (erst Spalten hinzufügen, erst in einer späteren Version entfernen).
- **Logs:** `docker compose -f /opt/gruppenkasse/docker-compose.yml logs -f app` (bzw. `caddy`, `db`).
- **Zertifikate:** Caddy holt und erneuert sie automatisch. Das Volume `caddy_data` darf nicht gelöscht werden. Beim ersten Ausprobieren in der Caddyfile
  `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` im globalen Block eintragen und danach wieder entfernen.
- **Überwachung:** Einen kostenlosen Uptime-Dienst auf `https://DOMAIN/actuator/health` einrichten, der bei Ausfall per E-Mail meldet. Das ist die einzige
  aktive Meldung: das Monitoring (siehe unten) läuft auf derselben VM und kann einen Totalausfall nicht selbst melden.
- **Passwort vergessen:** Es werden keine E-Mails verschickt. Ein Kassenverwalter erzeugt unter *Mitglieder → Passwort-Link* einen einmaligen Link (24 Stunden gültig)
  und gibt ihn der Person weiter. Für Kassenverwalter selbst geht das nur über den Admin; ein vergessenes Admin-Passwort setzt man per SQL zurück
  (`update person set passwort_hash = crypt('NEUES-PASSWORT', gen_salt('bf', 10)) where email = '…';`).
- **Updates der Abhängigkeiten** kommen wöchentlich als Dependabot-Pull-Requests.

## Monitoring (Grafana)

Auf der VM laufen neben der App Prometheus (Metriken, 30 Tage), Loki + Alloy (Logs, 14 Tage), Grafana und Exporter für Server, Container, Datenbank und
den HTTPS-Check, zusammen etwa 1,3 GB RAM. Nichts davon ist von außen erreichbar außer **Grafana unter `https://GRAFANA_DOMAIN`** (Anmeldung Pflicht).

- **Einrichten:** DNS-A-Record für `GRAFANA_DOMAIN` setzen, die Secrets `GRAFANA_DOMAIN` und `GRAFANA_ADMIN_PASSWORD` hinterlegen, deployen. Anmelden als `admin`.
- **Dashboards** (Ordner *technik* und *fachlich*): Übersicht, Anwendung, Datenbank, Server und Container, Logs, Belegerkennung, Betrieb, Sicherheit.
  Sie sind Dateien im Repo (`deploy/monitoring/grafana/dashboards`) und werden aus `deploy/monitoring/tools/gen_dashboards.py` erzeugt. Änderungen dort machen
  und `python deploy/monitoring/tools/gen_dashboards.py` ausführen; Änderungen in der Grafana-Oberfläche gehen verloren.
- **Alarmregeln** (`deploy/monitoring/prometheus/rules`): Es wird **nichts versendet**. Der Zustand steht in Grafana unter *Alerting → Alert rules* und als
  Tabelle auf dem Übersicht-Dashboard. Regeln testen: `promtool test rules deploy/monitoring/prometheus/tests/gruppenkasse.test.yml` (läuft auch in der CI).
- **Metriken** der App liegen auf dem internen Management-Port 8081 (`/actuator/prometheus`) und sind nie öffentlich. Fachliche Metriken (`gk_…`) zählen nur
  Vorgänge und Zeiten: keine Beträge, keine Personen, keine Wohngruppen.
- **Logs** sind JSON mit einer Request-ID (`X-Request-Id` in jeder Antwort). Eine Meldung eines Nutzers lässt sich so über die Request-ID im Logs-Dashboard finden.
- **Lokal ausprobieren:** `docker compose --profile monitoring up -d --build`, dann http://localhost:3000 (admin / admin). Auf Windows/macOS (Docker Desktop)
  liefern cAdvisor und node_exporter nur eingeschränkte Werte (Container- und Plattenpanels bleiben leer); auf der Linux-VM funktionieren sie.
- **Aufbewahrung:** Metriken 30 Tage (höchstens 3 GB), Logs 14 Tage. Volumes `prometheus_data`, `loki_data`, `grafana_data`.
- **Speicher knapp?** In `deploy/monitoring/docker-compose.monitoring.yml` die Limits senken oder das Profil entfernen (`COMPOSE_PROFILES` in `deploy.sh`).

## Wichtig: keine Backups in Version 1

Fällt der Server aus oder wird die Festplatte beschädigt, sind Buchungen und Belegfotos verloren. Die einfachste spätere Absicherung ist die buchbare
Backup-Option von Strato. Bis dahin hilft ein gelegentlicher manueller Auszug:

```bash
docker compose -f /opt/gruppenkasse/docker-compose.yml exec -T db pg_dump -U grouppurse grouppurse | gzip > kasse-$(date +%F).sql.gz
docker run --rm -v gruppenkasse_receipts:/data -v "$PWD":/out alpine tar czf /out/belege-$(date +%F).tgz -C /data .
```

## Datenschutz und Aufbewahrung (vor dem Start klären)

- Auftragsverarbeitungsvertrag mit dem Hosting-Anbieter und mit OpenAI (EU-Datenresidenz aktivieren), falls die Belegerkennung genutzt wird.
- Den Datenschutzhinweis in `frontend/src/components/PrivacyNotice.tsx` vom Verein bzw. Träger prüfen und anpassen lassen.
- Aufbewahrungsfrist (Standard 10 Jahre) vom Kassenprüfer oder Steuerberater bestätigen lassen.
- Vor dem Echtbetrieb die Belegerkennung mit 20 bis 30 echten Bons der Wohngruppe testen (auch verblasste und lange Bons) und prüfen, ob das gewählte Modell Bilder als Eingabe unterstützt.

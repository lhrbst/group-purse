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
| `OPENAI_API_KEY` | API-Schlüssel (nur nötig, wenn die Belegerkennung an ist) |
| `RECEIPT_AI_ENABLED` | `true` oder `false` |
| `RECEIPT_MODEL`, `RECEIPT_FALLBACK_MODEL` | Modellnamen, Standard `gpt-6-luna` / `gpt-6.1-sol` |

6. **Push auf `main`** startet die Pipeline: Tests, Image bauen, auf den Server kopieren und starten.
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

## Alltag

- **Deploys** laufen bei jedem Push auf `main` automatisch. Spring Boot braucht auf einem Kern 20 bis 40 Sekunden zum Start, deshalb bevorzugt abends deployen.
- **Rollback:** Schlägt der Health-Check (`/actuator/health`, 2 Minuten) fehl, setzt die Pipeline automatisch den vorigen Image-Tag.
  Manuell: GitHub → *Actions → Rollback* → *Run workflow* → gewünschten Tag (Git-Hash) eintragen.
- **Datenbank-Migrationen** (Flyway) laufen beim Start. Ein Rollback setzt nur die App zurück, nicht die Datenbank. Migrationen deshalb abwärtskompatibel schreiben
  (erst Spalten hinzufügen, erst in einer späteren Version entfernen).
- **Logs:** `docker compose -f /opt/gruppenkasse/docker-compose.yml logs -f app` (bzw. `caddy`, `db`).
- **Zertifikate:** Caddy holt und erneuert sie automatisch. Das Volume `caddy_data` darf nicht gelöscht werden. Beim ersten Ausprobieren in der Caddyfile
  `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` im globalen Block eintragen und danach wieder entfernen.
- **Überwachung:** Einen kostenlosen Uptime-Dienst auf `https://DOMAIN/actuator/health` einrichten, der bei Ausfall per E-Mail meldet.
- **Passwort vergessen:** Es werden keine E-Mails verschickt. Ein Kassenverwalter erzeugt unter *Mitglieder → Passwort-Link* einen einmaligen Link (24 Stunden gültig)
  und gibt ihn der Person weiter. Für Kassenverwalter selbst geht das nur über den Admin; ein vergessenes Admin-Passwort setzt man per SQL zurück
  (`update person set passwort_hash = crypt('NEUES-PASSWORT', gen_salt('bf', 10)) where email = '…';`).
- **Updates der Abhängigkeiten** kommen wöchentlich als Dependabot-Pull-Requests.

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

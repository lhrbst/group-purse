# Gruppenkasse

Web-App (PWA) für die Gruppenkasse eines Elternvereins: Elternbeiträge pro Familie und Monat, Barausgaben des Teams per Belegfoto,
lückenloses Kassenbuch für Barkasse und Vereinskonto, Auswertungen. Mehrere Wohngruppen sind strikt getrennt.

Vollständige Anforderungen: [`docs/Spezifikation Web-App Gruppenkasse für Wohngruppen.md`](docs/Spezifikation%20Web-App%20Gruppenkasse%20f%C3%BCr%20Wohngruppen.md)
(der Abschnitt „Entscheidungen zur Umsetzung“ hat Vorrang). Betrieb und Einrichtung: [`docs/betrieb.md`](docs/betrieb.md).
Der Umsetzungsplan liegt in `docs/superpowers/plans/`.

## Rollen

| Rolle | Kann |
| --- | --- |
| **Admin** (global) | alles in allen Wohngruppen; legt als Einziger Wohngruppen an und lädt Kassenverwalter ein. Wird einmalig per SQL angelegt. |
| **Kassenverwalter** (je Wohngruppe) | Beiträge und Kassenbuch führen, Ausgaben prüfen, Teammitglieder freischalten, Auswertungen |
| **Teammitglied** (je Wohngruppe; Pflege- und Assistenzkräfte oder wer sonst einkauft) | Belege erfassen, eigene Ausgaben sehen, Kassenstand sehen |

Eltern haben keinen Zugang.

## Technik

Spring Boot 4 (Java 25) mit Session-Cookie-Login, Hibernate-`@TenantId` für die Mandantentrennung, Flyway, PostgreSQL, Spring AI für die Belegerkennung;
React + Vite + TypeScript mit Mantine, TanStack Query und einem aus OpenAPI generierten Client (orval). Frontend und API laufen unter einer Domain
und werden als **ein** Docker-Image gebaut. Caddy davor sorgt für HTTPS.

## Lokal entwickeln

Voraussetzungen: JDK 25+, Node 24+, Docker (für PostgreSQL und die Testcontainers).

```bash
# Datenbank
docker run --name gk-db -e POSTGRES_USER=grouppurse -e POSTGRES_PASSWORD=grouppurse -e POSTGRES_DB=grouppurse -p 5432:5432 -d postgres:17-alpine

# Backend (Port 8080); COOKIE_SECURE=false, weil lokal ohne HTTPS
COOKIE_SECURE=false ./mvnw spring-boot:run

# Frontend mit Proxy auf das Backend (Port 5173)
cd frontend && npm ci && npm run dev
```

Den ersten Admin legst du wie in `docs/betrieb.md` beschrieben per SQL an.

### Tests

```bash
./mvnw verify                      # Backend, inkl. Testcontainers mit echtem PostgreSQL, Migrationen und Mandantenfilter
cd frontend && npm test            # Frontend (Vitest)
```

### API-Client neu erzeugen

Nach Änderungen an Controllern oder DTOs:

```bash
./mvnw -q test -Dtest=SpaAndOpenApiTest -Dopenapi.out=frontend/openapi.json   # schreibt den OpenAPI-Vertrag
cd frontend && npm run api:generate                                           # erzeugt src/api/generated und src/api/model
```

## Konfiguration

Alles über Umgebungsvariablen, siehe `src/main/resources/application.yml` und `deploy/env.example`. Wichtig:

| Variable | Zweck |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `APP_BASE_URL` | Basis für Einladungs- und Passwort-Links |
| `RECEIPT_AI_ENABLED`, `OPENAI_API_KEY`, `RECEIPT_MODEL`, `RECEIPT_FALLBACK_MODEL` | Belegerkennung; aus = manuelle Eingabe |
| `STORAGE_DIR` | Ablage der Belegfotos |

## Bewusst nicht enthalten (Version 1)

E-Mail- und Push-Benachrichtigungen (Hinweise erscheinen nur in der App), Export (PDF/CSV/ZIP), Bankabgleich, Erstattung privater Auslagen, Gruppenkarten, Verwaltung der Bewohner-Barbeträge, Kassensturz mit zwei Personen, Staging-Umgebung und **Backups**
(siehe Hinweis in `docs/betrieb.md`).

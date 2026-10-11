# Ende-zu-Ende-Tests (Playwright)

Die Suite testet die Gruppenkasse so, wie Menschen sie benutzen: im Browser, gegen den **vollständigen Stack**. Nichts davon ist gemockt außer dem
KI-Dienst, und der ist durch einen Ersatzserver mit derselben Schnittstelle ersetzt, damit die echte Belegverarbeitung im Backend (Spring AI,
Hintergrundjob, Wiederholungen, Timeout) deterministisch durchläuft.

## Was läuft

| Baustein | Wie |
| --- | --- |
| PostgreSQL | frischer Docker-Container (`gk-e2e-db`, Port 55432, Daten im RAM), wird nach dem Lauf gelöscht |
| Backend | das gebaute JAR (`target/group-purse-*.jar`) mit echten Flyway-Migrationen auf Port 18080 |
| Frontend | der **Produktions-Build** (`frontend/dist`) über `vite preview` auf Port 14173, `/api` geht ans Backend (mit Service Worker/PWA) |
| KI | Fake-OpenAI auf Port 19090 (`support/fake-llm.ts`), steuerbar: Erfolg, Fehler, ungültige Antwort, falsche Summen, zu langsam |
| Admin | wird direkt in der Datenbank angelegt, wie im Betriebshandbuch beschrieben |

Jeder Test legt über die API seine **eigene Wohngruppe** samt Kassenverwalter und Teammitglied an und braucht dadurch keine Aufräumarbeit.
Die Tests laufen nacheinander (ein Stack), das Backend darf zwischen den Tests aber im Hintergrund weiterarbeiten: Tests mit KI-Zählern warten
vorher, bis keine Belege mehr in Verarbeitung sind.

## Starten

Voraussetzungen: Docker (läuft), JDK 25+, Node 24+. Einmalig:

```bash
cd e2e
npm ci
npm run install:browsers      # lädt Chromium für Playwright
```

Dann:

```bash
npm test                      # baut JAR und Frontend und führt alles aus (Desktop + Mobil)
npm run test:desktop          # nur Desktop-Chrome
npm run test:mobile           # nur Pixel-7-Emulation
npm run test:ui               # Playwright-UI zum Debuggen
npm run test:headed           # sichtbarer Browser
npm run report                # HTML-Bericht des letzten Laufs
SKIP_BUILD=1 npm test         # ohne erneutes Bauen, wenn sich nichts geändert hat
npx playwright test tests/cash.spec.ts -g "cancelling"   # einzelne Tests
```

Logs des Stacks liegen nach dem Lauf in `e2e/.run/` (`backend.log`, `web.log`); bei Fehlschlägen liegen Screenshots, Videos und Traces in
`e2e/test-results/`. Andere Ports: `E2E_DB_PORT`, `E2E_BACKEND_PORT`, `E2E_LLM_PORT`, `E2E_WEB_PORT`.

## Was getestet wird

| Datei | Inhalt |
| --- | --- |
| `auth.spec.ts` | Anmelden/Abmelden ohne Fehlermeldung, Weiterleitung, Schutz vor offenem Redirect, Registrierung mit Beitrittscode, Freischaltung, Passwort-Reset per Link |
| `admin.spec.ts` | Wohngruppe anlegen, Einladungslink, Annahme, Gruppenwechsel des Admins, bestehendes Konto einladen |
| `members.spec.ts` | Sperren/Freischalten, neuer Beitrittscode macht den alten ungültig |
| `roles.spec.ts` | Navigation und Seitenzugriff je Rolle, API-Rechte, Vereinskonto nur für Kassenverwalter |
| `security.spec.ts` | 401/403, CSRF, Login-Sperre, Cookies und Header, XSS-Ausgabe, Upload-Prüfung, Zugriff auf Belegfotos |
| `cash.spec.ts` | Anfangsbestand, Auffüllung, Rückgabe, Bestand erfassen mit Differenz, Storno, Validierung, Filter, Kassenwarnung, Minus |
| `contributions.spec.ts` | Familien, Raster, Verteilung auf die ältesten offenen Monate, Überfälligkeit, Guthaben, Storno, Sollbetrag |
| `categories.spec.ts` | Unterkategorien, Ein-/Ausblenden, eigene Kategorien, Trennung je Gruppe |
| `receipts-manual.spec.ts` | Aufteilung in Positionen, Summe, Beleg ohne Foto, Bearbeiten/Prüfen/Stornieren, Kontoausgabe |
| `receipts-async.spec.ts` | Nur-Foto-Erfassung, Hintergrundverarbeitung, Fehler/Timeout/falsche Summen, Unsicherheit der KI, mehrere Bons auf einem Foto, manuelle Erfassung, Foto ersetzen, mehrere Fotos |
| `offline-upload.spec.ts` | Foto-Warteschlange im Gerät: ohne Netz, Neuladen, Ablehnung durch den Server, abgelaufene Sitzung |
| `reports.spec.ts` | Diagramme, Haupt-/Unterkategorie, Filter |
| `isolation.spec.ts` | Wohngruppen sehen nichts voneinander, eine Person in mehreren Gruppen |
| `pwa.spec.ts` | Manifest, Icons, Service Worker, Offline-Start, keine zwischengespeicherten API-Antworten |
| `observability.spec.ts` | Metriken nur auf dem Management-Port, Geschäftszähler nach Beleg-Durchlauf, keine Personen/Beträge in Metriken, Request-ID |
| `smoke.spec.ts` | Jede Seite jeder Rolle lädt ohne Skriptfehler |
| `mobile.mobile.spec.ts` | Burger-Menü, Erfassen am Handy, kein seitliches Scrollen, Prüfen am Handy |

## Neue Tests schreiben

```ts
import { test, expect, openAs } from '../support/fixtures';

test('manager sees the balance', async ({ page, world }) => {
  await world.manager.api.setBalances(100, 500);      // Testdaten über die API
  await openAs(page, world.manager, '/kasse');          // eingeloggt ohne Login-Formular
  await expect(page.getByText('100,00 €').first()).toBeVisible();
});
```

- `world` liefert `groupId`, `groupName`, `code`, `manager`, `member` (je mit `email`, `password`, `api`) und `addMember()`.
- `llm` steuert die Fake-KI: `await llm.mode('fail')`, `llm.mode('slow', 2000)`, `llm.receipt({...})`, `await llm.calls()`.
- `support/ui.ts` enthält Helfer für Mantine (`pick`, `toggleSwitch`, `dialog`, `toast`, `eur`).

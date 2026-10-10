# Gruppenkasse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Web-App (PWA), mit der Kassenverwalter eines Elternvereins Vereinskonto, Barkasse und Elternbeiträge pro Wohngruppe führen und Pflegekräfte Barausgaben per Belegfoto erfassen.

**Architecture:** Ein Spring-Boot-Monolith (REST-API + ausgeliefertes React-SPA) mit PostgreSQL. Mandantentrennung über Hibernate `@TenantId` (Wohngruppen-ID aus der Session). Die Barkasse ist ein append-only Kassenbuch; Stände werden immer aus der Summe der Buchungen berechnet.

**Tech Stack:** Java 25, Spring Boot 4, Spring Security (Session-Cookie), Spring Data JPA, Flyway, PostgreSQL, Spring AI (OpenAI), springdoc-openapi, webpush-java, spring-boot-starter-mail, OpenPDF, JUnit + Testcontainers; React + Vite + TypeScript, Mantine (+charts, form), TanStack Query, zod, orval, vite-plugin-pwa; Docker Compose, Caddy, GitHub Actions.

**Spec:** `docs/Spezifikation Web-App Gruppenkasse für Wohngruppen.md` (der Abschnitt „Entscheidungen zur Umsetzung“ hat Vorrang vor älteren Abschnitten).

## Global Constraints

- Backend: Spring Boot 4, Java 25, Maven; Basis-Package `de.grouppurse`.
- Auth: Session-Cookie, kein JWT, kein CORS; Frontend und API unter derselben Domain.
- Mandantentrennung: Hibernate `@TenantId` + `CurrentTenantIdentifierResolver`, aktive Gruppe aus der Session. Keine Native Queries auf mandantenbezogenen Tabellen (Hibernate filtert sie nicht).
- Migrationen: Flyway, abwärtskompatibel (erst Spalten hinzufügen, später entfernen).
- Buchungen werden nie gelöscht, nur durch Storno ausgeglichen; jede Änderung kommt ins Audit-Log.
- Kassenstand = Summe aller Buchungen des Kontos. Beträge `NUMERIC(12,2)`, in Java `BigDecimal`.
- UI nur Deutsch. Bewohner nur als Vorname/Kürzel, keine Gesundheitsdaten.
- Belegfotos: Docker-Volume, Auslieferung nur über die API mit Rechteprüfung (Kassenverwalter, Admin, erfassende Pflegekraft).
- Rollen: global `Admin` (Person.ist_admin); pro Wohngruppe `kassenverwalter` / `pflegekraft` mit Status `wartend|aktiv|gesperrt`. Nur der Admin legt Wohngruppen an und ernennt Kassenverwalter. Kein Kassensturz.
- Server: 1 vCore / 2 GB RAM: `-Xmx512m -XX:+UseSerialGC`, Hikari-Pool 5.
- KI-Modellnamen und KI-Aktivierung sind Konfiguration (`app.receipt.*`).

## Datenmodell-Entscheidungen (verbindlich für alle Tasks)

- `wohngruppe`, `person`, `mitgliedschaft`, `einladung`, `passwort_reset_token`, `kategorie_global`: **ohne** `@TenantId` (Zugriff wird im Service gegen Mitgliedschaft geprüft).
- Alle übrigen Tabellen tragen `wohngruppe_id` mit `@TenantId`: `kategorie_gruppe`, `familie`, `sollbetrag`, `beitrag`, `buchung`, `audit_log`, `push_subscription`.
- `buchung.betrag` ist **vorzeichenbehaftet aus Sicht des Kontos** (+ erhöht, − mindert). `buchung.nummer` ist je Wohngruppe fortlaufend.
- Auffüllung/Rückgabe = zwei Buchungen mit gleicher `gruppe_id` (UUID), eine je Konto.
- Vereinskonto-Stand = Σ(buchung auf vereinskonto) + Σ(beitrag.betrag).

---

### Task 1: Projektgerüst, Testinfrastruktur, Health

**Files:**
- Create: `pom.xml`, `src/main/java/de/grouppurse/GroupPurseApplication.java`, `src/main/resources/application.yml`, `src/test/java/de/grouppurse/AbstractIntegrationTest.java`, `src/test/java/de/grouppurse/HealthTest.java`, `.gitignore`, `.editorconfig`

**Interfaces:**
- Produces: `AbstractIntegrationTest` (Basisklasse: `@SpringBootTest(webEnvironment=RANDOM_PORT)`, `@Testcontainers`, statischer `PostgreSQLContainer`, `@DynamicPropertySource`, Feld `MockMvc mvc`, `@AutoConfigureMockMvc`). Alle späteren Integrationstests erben davon.

- [ ] **Step 1:** `git init` im Projektstamm, `.gitignore` (target/, node_modules/, .env, .idea/, frontend/dist, data/).
- [ ] **Step 2:** `pom.xml` mit Parent `spring-boot-starter-parent` (aktuelle 4.x-Version über https://start.spring.io prüfen), `java.version=25`, Dependencies: web, security, data-jpa, validation, actuator, mail, flyway-core + flyway-database-postgresql, postgresql, spring-ai-starter-model-openai (BOM aus dem Spring-AI-Release, der zu Boot 4 passt), springdoc-openapi-starter-webmvc-ui, `nl.martijndwars:web-push`, `com.github.librepdf:openpdf`; test: spring-boot-starter-test, spring-security-test, spring-boot-testcontainers, testcontainers-postgresql, testcontainers-junit-jupiter. Plugin: `spring-boot-maven-plugin`.
- [ ] **Step 3:** `application.yml`: Datasource aus `${DB_URL}`, `${DB_USER}`, `${DB_PASSWORD}`; `spring.jpa.hibernate.ddl-auto=validate`; `spring.jpa.open-in-view=false`; Hikari `maximum-pool-size: 5`; `management.endpoints.web.exposure.include=health`; `server.servlet.session.cookie.same-site=lax`, `http-only=true`, `secure=${COOKIE_SECURE:true}`; `spring.servlet.multipart.max-file-size=20MB`.
- [ ] **Step 4:** Failing test `HealthTest`: `GET /actuator/health` → 200 und `"status":"UP"`. Run: `./mvnw -q test -Dtest=HealthTest` → FAIL (keine Klasse/Migration).
- [ ] **Step 5:** `GroupPurseApplication` + leere Migration `src/main/resources/db/migration/V1__init.sql` (`SELECT 1;`), damit Flyway startet. Test → PASS.
- [ ] **Step 6:** Commit `chore: project skeleton with testcontainers`.

---

### Task 2: Schema V1 und Entities

**Files:**
- Create/Replace: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/main/java/de/grouppurse/domain/*.java` (Entities je eine Datei), `src/main/java/de/grouppurse/repo/*Repository.java`
- Test: `src/test/java/de/grouppurse/MigrationTest.java`

**Interfaces:**
- Produces Entities (Package `de.grouppurse.domain`), alle mit `UUID id`:
  - `Person(name, email unique lowercased, passwortHash, istAdmin, datenschutzZugestimmtAm)`
  - `Wohngruppe(name, beitrittscode, mindestbetragKasse BigDecimal default 50.00)`
  - `Mitgliedschaft(person, wohngruppe, Rolle rolle, MitgliedStatus status, freigeschaltetVon, freigeschaltetAm)`; enums `Rolle{KASSENVERWALTER,PFLEGEKRAFT}`, `MitgliedStatus{WARTEND,AKTIV,GESPERRT}`
  - `Einladung(token, email, wohngruppe, rolle, gueltigBis, eingeloestAm)`
  - `PasswortResetToken(tokenHash, person, gueltigBis, eingeloestAm)`
  - `KategorieGlobal(name)`; `KategorieGruppe(@TenantId UUID wohngruppeId, name, UUID globalRef nullable, boolean ausgeblendet)` — eigene Kategorie: `globalRef==null`; Ausblenden einer globalen: Zeile mit `globalRef` gesetzt und `ausgeblendet=true`
  - `Familie(@TenantId wohngruppeId, name, bewohnerKuerzel, kontaktEmail, faelligkeitstag int 1–28, aktiv)`
  - `Sollbetrag(@TenantId wohngruppeId, UUID familieId, BigDecimal betrag, LocalDate gueltigAb)`
  - `Beitrag(@TenantId wohngruppeId, UUID familieId, BigDecimal betrag, LocalDate eingangsdatum, List<YearMonth> monate, String notiz, UUID gebuchtVon)` (monate als `text[]`/`yyyy-MM`)
  - `Buchung(@TenantId wohngruppeId, long nummer, Konto konto, Art art, BigDecimal betrag, LocalDate datum, UUID kategorieId, String zweck, String haendler, String bewohnerKuerzel, BuchungStatus status, List<String> belegFotos, String begruendungOhneBeleg, String ablehnungsKommentar, UUID stornoVon, UUID gruppeId, UUID erfasstVon, Instant erfasstAm, UUID geprueftVon, Instant geprueftAm)`; enums `Konto{BARKASSE,VEREINSKONTO}`, `Art{ANFANGSBESTAND,AUFFUELLUNG,AUSGABE,RUECKGABE,KORREKTUR,KONTOAUSGABE,STORNO}`, `BuchungStatus{GEBUCHT,EINGEREICHT,GEPRUEFT,ABGELEHNT,STORNIERT}`
  - `AuditLog(@TenantId wohngruppeId, UUID wer, Instant wann, String was, String entitaet, UUID entitaetId, String alt, String neu)`
  - `PushSubscription(@TenantId wohngruppeId, UUID personId, endpoint, p256dh, auth)`
- Alle Repositories sind `JpaRepository<X, UUID>`.

- [ ] **Step 1:** Failing `MigrationTest`: Spring-Context startet; `JdbcTemplate` fragt `select count(*) from information_schema.tables where table_name in ('person','wohngruppe','mitgliedschaft','einladung','passwort_reset_token','kategorie_global','kategorie_gruppe','familie','sollbetrag','beitrag','buchung','audit_log','push_subscription')` → erwartet 13. Run → FAIL.
- [ ] **Step 2:** `V1__init.sql` schreiben. Wichtige Constraints: `unique(wohngruppe_id, nummer)` auf `buchung`; `unique(person_id, wohngruppe_id)` auf `mitgliedschaft`; `unique(beitrittscode)`; `check(faelligkeitstag between 1 and 28)`; `check (konto in (...))`, `check (art in (...))`, `check(status in (...))`; FK `storno_von → buchung(id)`; Index `(wohngruppe_id, konto)` auf `buchung`; `seed`: `insert into kategorie_global(id,name)` für die acht Standardkategorien (Lebensmittel, Drogerie und Hygiene, Freizeit und Ausflüge, Fahrten, Haushalt, Anschaffungen, Geschenke und Feiern, Sonstiges). Alle Tabellen mit `wohngruppe_id` haben FK auf `wohngruppe`.
- [ ] **Step 3:** Entities und Repositories anlegen (Jakarta Persistence; `@TenantId` auf `wohngruppeId` der mandantenbezogenen Entities; Enums `@Enumerated(STRING)`).
- [ ] **Step 4:** Test → PASS (inkl. `ddl-auto=validate` als Schema/Entity-Abgleich).
- [ ] **Step 5:** Commit `feat: schema v1 and entities`.

---

### Task 3: Mandantenfilter (Tenant-Resolver) mit Isolationstest

**Files:**
- Create: `src/main/java/de/grouppurse/tenant/TenantContext.java`, `TenantResolver.java`, `TenantConfig.java`
- Test: `src/test/java/de/grouppurse/tenant/TenantIsolationTest.java`

**Interfaces:**
- Produces: `TenantContext.set(UUID)`, `TenantContext.get(): Optional<UUID>`, `TenantContext.clear()`, `TenantContext.runAs(UUID tenant, Runnable r)`; `TenantResolver implements CurrentTenantIdentifierResolver<UUID>` (liefert `TenantContext`-Wert, sonst Sentinel `UUID(0,0)`, validiert nie); Konstante `TenantContext.NONE = new UUID(0,0)`.
- Konsument: `TenantFilter` (Task 4) setzt den Kontext pro Request aus der Session.

- [ ] **Step 1:** Failing Test: zwei `Wohngruppe` anlegen (A, B), mit `TenantContext.runAs(A, ...)` je eine `Familie` speichern. Dann: unter A ist `familieRepo.findAll()` genau 1 Eintrag (die von A); unter B genau 1 (die von B); `findById(idVonA)` unter B → leer; ohne Kontext → Speichern wirft Exception (Sentinel verletzt FK `wohngruppe_id`).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implementierung. `TenantResolver`:
```java
@Component
public class TenantResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {
    public UUID resolveCurrentTenantIdentifier() { return TenantContext.get().orElse(TenantContext.NONE); }
    public boolean validateExistingCurrentSessions() { return false; }
    public void customize(Map<String,Object> p) { p.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this); }
}
```
- [ ] **Step 4:** Test → PASS. Commit `feat: hibernate tenant isolation`.

---

### Task 4: Authentifizierung, Registrierung, aktive Gruppe, Rollenprüfung

**Files:**
- Create: `security/SecurityConfig.java`, `security/CurrentUser.java`, `security/TenantFilter.java`, `security/Access.java`, `auth/AuthController.java`, `auth/AuthService.java`, `auth/dto/*.java`, `auth/PasswordResetService.java`, `mail/MailService.java`
- Test: `auth/AuthFlowTest.java`

**Interfaces:**
- Produces:
  - `CurrentUser` (Bean, request-scoped Zugriff): `UUID personId()`, `boolean istAdmin()`, `Optional<UUID> activeGroup()`, `Optional<Rolle> activeRole()`.
  - `Access`: `requireKassenverwalter()` (Admin gilt immer), `requireActiveMember()` (Rolle KASSENVERWALTER oder PFLEGEKRAFT mit Status AKTIV, oder Admin), `requireAdmin()`; wirft `ResponseStatusException(403)`.
  - REST: `POST /api/auth/register {name,email,password,beitrittscode,datenschutzZugestimmt}` (registriert Pflegekraft, Status WARTEND — Details Task 6), `POST /api/auth/login {email,password}`, `POST /api/auth/logout`, `GET /api/me → MeDto{personId,name,istAdmin,mitgliedschaften[{wohngruppeId,wohngruppeName,rolle,status}],activeGroupId}`, `POST /api/me/active-group {wohngruppeId}`, `POST /api/auth/password-reset/request {email}`, `POST /api/auth/password-reset/confirm {token,newPassword}`.
  - `MailService.send(String to, String subject, String text)` (SMTP über `JavaMailSender`; `app.mail.from`; im Test per `@MockitoBean`).
- Regeln: Passwort min. 10 Zeichen, BCrypt. Login per Spring-Security-Formular-Alternative (JSON-Login über eigenen Controller mit `SecurityContextRepository`, CSRF per `CookieCsrfTokenRepository` aktiv, Frontend sendet `X-XSRF-TOKEN`). `TenantFilter` setzt/leert `TenantContext` aus Session-Attribut `activeGroupId`. `active-group` wird nur gesetzt, wenn eine aktive Mitgliedschaft besteht oder Admin. Bei Login wird, wenn genau eine aktive Mitgliedschaft existiert, diese als aktive Gruppe gesetzt. Reset-Token: 32 Byte Zufall, nur SHA-256-Hash gespeichert, 1 h gültig, einmalig; `request` antwortet immer 204.

- [ ] **Step 1:** Failing `AuthFlowTest` (Tests als Liste, je ein `@Test`): Login mit falschem Passwort → 401; Login korrekt → 200 + Session, `GET /api/me` liefert Person; `GET /api/me` ohne Login → 401; `active-group` einer fremden Gruppe → 403; Admin darf jede Gruppe wählen; Logout beendet Session; Reset-Flow: `request` erzeugt Mail (Mock verifiziert Link mit Token), `confirm` setzt neues Passwort, zweites `confirm` mit gleichem Token → 400.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implementieren (Reihenfolge: SecurityConfig, CurrentUser/TenantFilter, AuthService, Controller, Reset, Mail). Alle `/api/**` außer `/api/auth/**` brauchen Login; statische Ressourcen und SPA-Fallback (`/**` → `index.html`, per `WebMvcConfigurer`) sind frei.
- [ ] **Step 4:** Test → PASS. Commit `feat: session auth, active group, password reset`.

---

### Task 5: Admin — Wohngruppen anlegen, Kassenverwalter einladen

**Files:**
- Create: `admin/AdminController.java`, `admin/WohngruppeService.java`, `admin/InvitationService.java`, `admin/dto/*.java`, `admin/BeitrittscodeGenerator.java`
- Test: `admin/AdminFlowTest.java`, `admin/BeitrittscodeGeneratorTest.java`
- Create (Initialer Admin): `docs/betrieb.md` mit dem SQL `update person set ist_admin = true where email = '...';`

**Interfaces:**
- Consumes: `Access.requireAdmin()`.
- Produces REST (alle nur Admin): `POST /api/admin/wohngruppen {name} → WohngruppeDto{id,name,beitrittscode,mindestbetragKasse}`, `GET /api/admin/wohngruppen`, `POST /api/admin/wohngruppen/{id}/kassenverwalter-einladung {email} → 204` (Mail mit Link `${app.base-url}/einladung/{token}`; Token 7 Tage gültig), `GET /api/einladungen/{token} → {email, wohngruppeName, kontoExistiert}` (öffentlich), `POST /api/einladungen/{token}/einloesen {name?, password?}` (öffentlich; legt Person an oder ergänzt bestehende; erzeugt Mitgliedschaft KASSENVERWALTER/AKTIV; Token danach ungültig).
- `BeitrittscodeGenerator.generate(): String` — 8 Zeichen aus `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (ohne 0/O/1/I), `SecureRandom`.

- [ ] **Step 1:** Failing Tests: Nicht-Admin → 403 auf alle Admin-Endpunkte; Admin legt Gruppe an, Code hat 8 Zeichen und ist eindeutig; Einladung → Mail an Mock; Einlösen mit neuem Konto erzeugt Person + aktive Kassenverwalter-Mitgliedschaft; Einlösen mit bestehendem Konto (kein Passwort nötig, aber nur wenn eingeloggt als diese Person, sonst 401) ergänzt nur die Mitgliedschaft; abgelaufener oder bereits eingelöster Token → 410. Generator-Test: 1000 Codes, alle 8 Zeichen, nur erlaubte Zeichen.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementieren. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat: admin creates groups and invites kassenverwalter`.

---

### Task 6: Pflegekräfte — Registrierung per Code, Freischalten, Sperren, Code erneuern, Gruppen-Einstellungen

**Files:**
- Create: `members/MemberController.java`, `members/MemberService.java`, `settings/SettingsController.java`; Modify: `auth/AuthService.java` (Register-Logik)
- Test: `members/MemberFlowTest.java`

**Interfaces:**
- Produces REST (Kassenverwalter der aktiven Gruppe oder Admin): `GET /api/members → [{mitgliedschaftId,personId,name,email,rolle,status}]`, `POST /api/members/{id}/freischalten`, `POST /api/members/{id}/sperren`, `POST /api/group/beitrittscode/neu → {beitrittscode}`, `GET /api/group → {id,name,beitrittscode(nur KV),mindestbetragKasse}`, `PUT /api/group/settings {mindestbetragKasse}`.
- Registrierung (`POST /api/auth/register`): sucht Wohngruppe per Beitrittscode (Groß-/Kleinschreibung egal); unbekannter Code → 400 ohne Hinweis auf Existenz weiterer Daten; legt Person (oder, bei existierender E-Mail mit korrektem Passwort, nur Mitgliedschaft) und Mitgliedschaft PFLEGEKRAFT/WARTEND an; `datenschutzZugestimmtAm` wird gesetzt, ohne Zustimmung → 400.
- Regeln: `WARTEND` und `GESPERRT` dürfen außer `GET /api/me` und `/api/auth/*` nichts (Access.requireActiveMember prüft Status). Gesperrte werden nie gelöscht.

- [ ] **Step 1:** Failing Tests (Register mit falschem Code → 400; wartende Pflegekraft bekommt 403 auf `/api/group`; Freischalten setzt AKTIV + `freigeschaltetVon/Am`; gesperrte Pflegekraft 403, Name bleibt in `GET /api/members`; Pflegekraft darf `/api/members` nicht (403); neuer Code macht alten ungültig).
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: caregiver registration and approval`.

---

### Task 7: Kassenbuch-Kern (Ledger), Audit-Log, Storno

**Files:**
- Create: `ledger/LedgerService.java`, `ledger/BalanceService.java`, `ledger/BookingNumberService.java`, `audit/AuditService.java`, `ledger/LedgerController.java`, `ledger/dto/*.java`
- Test: `ledger/LedgerServiceTest.java`, `ledger/LedgerControllerTest.java`

**Interfaces:**
- Produces:
  - `BalanceService.balance(Konto): BigDecimal` (Barkasse = Σ buchung.betrag; Vereinskonto = Σ buchung.betrag + Σ beitrag.betrag; jeweils aktive Gruppe). Per `@Query("select coalesce(sum(b.betrag),0) from Buchung b where b.konto = :k")` im Repository (JPQL, wird gefiltert).
  - `LedgerService`:
    - `Buchung anfangsbestand(Konto konto, BigDecimal betrag, LocalDate datum)` — nur einmal je Konto (sonst 409)
    - `List<Buchung> auffuellung(BigDecimal betrag, LocalDate datum, String uebergebenAn)` — Vereinskonto −betrag, Barkasse +betrag, gleiche `gruppeId`, `zweck=uebergebenAn`
    - `List<Buchung> rueckgabe(BigDecimal betrag, LocalDate datum, String grund)` — Barkasse −, Vereinskonto +
    - `Buchung korrektur(Konto konto, BigDecimal betrag /*vorzeichenbehaftet, ≠0*/, LocalDate datum, String begruendung /*Pflicht*/)`
    - `BestandResult bestandErfassen(Konto konto, BigDecimal istBestand, String begruendung)` → `{soll, ist, differenz, Buchung korrektur|null}`; Differenz ≠ 0 ⇒ Korrektur (bei Differenz Begründung Pflicht, bei Differenz 0 keine Buchung); gibt es noch keine Buchung/Beitrag auf dem Konto, wird stattdessen ein Anfangsbestand gebucht
    - `List<Buchung> storno(UUID buchungId, String begruendung)` — Gegenbuchung (`art=STORNO`, `betrag=-orig`, `stornoVon=orig.id`, gleiche `gruppeId`-Partner werden mit storniert); Original-Status `STORNIERT`; bereits stornierte oder Storno-Buchungen → 409
  - `BookingNumberService.next()` — sperrt Zeile der Wohngruppe (`PESSIMISTIC_WRITE`) und vergibt `max(nummer)+1`.
  - `AuditService.log(String was, String entitaet, UUID id, Object alt, Object neu)` (JSON-Strings, `wer` = CurrentUser).
  - REST (nur Kassenverwalter/Admin): `POST /api/ledger/anfangsbestand`, `/auffuellung`, `/rueckgabe`, `/korrektur`, `/bestand`, `POST /api/ledger/{id}/storno`; Lesen (alle aktiven Mitglieder): `GET /api/balance → {barkasse, vereinskonto?(nur KV), mindestbetrag, warnung:boolean, negativ:boolean}`, `GET /api/ledger?von&bis&konto → [BuchungDto]` (nur KV); Pflegekräfte sehen Vereinskonto nicht.

- [ ] **Step 1:** Failing Tests (`LedgerServiceTest`, unter `TenantContext.runAs`):
```java
@Test void auffuellungMovesMoneyBetweenAccounts() {
    ledger.anfangsbestand(Konto.VEREINSKONTO, bd("1000.00"), today);
    ledger.auffuellung(bd("200.00"), today, "Kasse direkt");
    assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("200.00");
    assertThat(balance.balance(Konto.VEREINSKONTO)).isEqualByComparingTo("800.00");
}
@Test void stornoCancelsBothLegsAndIsNotRepeatable() { /* auffuellung, storno -> beide Stände zurück; zweites storno -> 409 */ }
@Test void bestandErfassenBooksCorrectionForDifference() { /* Soll 200, Ist 180, Begründung -> korrektur -20, balance 180 */ }
@Test void bestandErfassenFirstTimeBooksOpeningBalance() { /* leere Barkasse, Ist 150 -> anfangsbestand */ }
@Test void korrekturRequiresReason() { /* leer -> IllegalArgument/400 */ }
@Test void numbersAreSequentialPerGroup() { /* zwei Gruppen, je 1,2,3 */ }
@Test void anfangsbestandOnlyOncePerAccount() { /* zweiter -> 409 */ }
```
Controller-Test: Pflegekraft → 403 auf alle schreibenden Endpunkte; `GET /api/balance` liefert für Pflegekraft kein `vereinskonto`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementieren (jede mutierende Methode `@Transactional`, ruft `AuditService.log`). **Step 4:** PASS. **Step 5:** Commit `feat: ledger core with audit and storno`.

---

### Task 8: Kategorien (global + gruppeneigen)

**Files:**
- Create: `category/CategoryService.java`, `category/CategoryController.java`
- Test: `category/CategoryServiceTest.java`

**Interfaces:**
- Produces: `CategoryService.visible(): List<CategoryDto{id, name, global:boolean}>` (alle globalen außer in dieser Gruppe ausgeblendeten, plus eigene nicht ausgeblendete); `UUID add(String name)`; `hide(UUID id, boolean hidden)` (für globale: legt/ändert `KategorieGruppe` mit `globalRef`; für eigene: Flag); `boolean exists(UUID id)` (für Validierung in Task 9/10 — nur sichtbare Kategorien sind zulässig). REST: `GET /api/categories` (alle aktiven Mitglieder), `POST /api/categories {name}` und `PUT /api/categories/{id}/hidden {hidden}` (KV).
- IDs globaler und eigener Kategorien sind UUIDs derselben Domäne in `buchung.kategorie_id` (kein FK, Validierung im Service).

- [ ] **Step 1:** Failing Tests: Standardliste hat 8 Einträge; Gruppe A blendet „Sonstiges“ aus → A sieht 7, B weiterhin 8; Gruppe A fügt „Garten“ hinzu → nur in A sichtbar; doppelter Name (case-insensitive) → 409.
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: categories`.

---

### Task 9: Ausgaben mit Belegen und Prüf-Workflow

**Files:**
- Create: `expense/ExpenseService.java`, `expense/ExpenseController.java`, `expense/ReceiptStorage.java`, `expense/dto/*.java`
- Test: `expense/ExpenseWorkflowTest.java`, `expense/ReceiptAccessTest.java`

**Interfaces:**
- Consumes: `LedgerService` (Nummernvergabe, Audit), `CategoryService.exists`, `Access`.
- Produces:
  - `ReceiptStorage.store(MultipartFile): String` (Dateiname `UUID.ext`, Ablage unter `app.storage.dir/{wohngruppeId}/`; nur `image/webp|jpeg|png`, max. 5 MB je Foto, Magic-Bytes-Prüfung), `Resource load(String name)`.
  - `ExpenseService`:
    - `Buchung submit(ExpenseInput in, List<MultipartFile> fotos)` — Pflichtfelder Betrag > 0, Datum ≤ heute, Kategorie sichtbar; ohne Fotos nur mit `begruendungOhneBeleg` (sonst 400). Speichert `BARKASSE`, `ausgabe`, `betrag = -in.betrag`, Status `EINGEREICHT`.
    - `Buchung resubmit(UUID id, ExpenseInput in, fotos)` — nur Ersteller, nur Status `ABGELEHNT`; Status → `EINGEREICHT`, Ablehnungskommentar bleibt im Audit.
    - `approve(UUID id)` (Status `GEPRUEFT`, `geprueftVon/Am`), `reject(UUID id, String kommentar /*Pflicht*/)` (Status `ABGELEHNT`; **Betrag bleibt im Kassenstand**).
    - `List<ExpenseDto> mine(int days)`; `Page<ExpenseDto> all(filter)` (nur KV); `List<ExpenseDto> pending()`.
  - REST: `POST /api/expenses` (multipart: Teil `data` JSON, Teile `photos[]`), `PUT /api/expenses/{id}` (resubmit), `POST /api/expenses/{id}/approve`, `/reject {kommentar}`, `GET /api/expenses/mine`, `GET /api/expenses?status&kategorie&von&bis&person&bewohner`, `GET /api/expenses/pending`, `GET /api/receipts/{buchungId}/{index}` (Rechteprüfung: KV/Admin oder `erfasstVon == currentUser`, sonst 403/404).
- Regeln: Pflegekraft kann `eingereicht`/`geprueft` nicht ändern (403/409). Alle Zustandswechsel auditiert. Ausgabe führt den Kassenstand sofort herunter (`BalanceService` unverändert).

- [ ] **Step 1:** Failing Tests: Einreichen mindert Barkasse sofort, Status EINGEREICHT; ohne Foto und ohne Begründung → 400; ohne Foto mit Begründung → ok und in `pending()` mit Flag `ohneBeleg=true`; Datum in der Zukunft → 400; Pflegekraft ändert eingereichte Ausgabe → 403/409; Ablehnung → Status ABGELEHNT, Kassenstand unverändert, Pflegekraft kann per `PUT` neu einreichen; Prüfen setzt GEPRUEFT, danach kein Ablehnen mehr (409); fremde Pflegekraft kann Beleg nicht abrufen (403), erfassende und KV können; Pflegekraft sieht in `mine` nur eigene; Kassenverwalter-Ausgabe ist ebenfalls möglich (Spec: „Pflegekraft, Kassenverwalter“).
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: expenses with receipts and review workflow`.

---

### Task 10: Kontoausgaben

**Files:**
- Create: `ledger/AccountExpenseService.java` (oder Methode in `LedgerService`), Endpoint `POST /api/ledger/kontoausgabe` (multipart wie Task 9)
- Test: `ledger/AccountExpenseTest.java`

**Interfaces:**
- Produces: `Buchung kontoausgabe(BigDecimal betrag, LocalDate datum, UUID kategorieId, String zweck, List<MultipartFile> fotos)` — Konto `VEREINSKONTO`, `betrag = -betrag`, Status `GEBUCHT`, Beleg Pflicht (oder Begründung wie Task 9). Storno nutzt `LedgerService.storno`.

- [ ] **Step 1:** Failing Tests: Vereinskonto sinkt, Barkasse unverändert; Pflegekraft → 403; Kategorie Pflicht. **Step 2–4:** FAIL → PASS. **Step 5:** Commit `feat: bank account expenses`.

---

### Task 11: Familien, Sollbeträge, Elternbeiträge, Raster

**Files:**
- Create: `contributions/FamilyService.java`, `contributions/ContributionService.java`, `contributions/GridService.java`, `contributions/ContributionController.java`, `contributions/dto/*.java`
- Test: `contributions/GridServiceTest.java`, `contributions/ContributionServiceTest.java`

**Interfaces:**
- Produces:
  - `FamilyService`: `create(name, bewohnerKuerzel, kontaktEmail, faelligkeitstag, sollbetrag, gueltigAb)`, `update(...)`, `deaktivieren(id)`, `setSollbetrag(familieId, betrag, gueltigAb)` (neue Zeile in `sollbetrag`, alte bleiben), `BigDecimal sollFor(UUID familieId, YearMonth m)` (neuester Eintrag mit `gueltigAb <= erster Tag von m`, sonst 0).
  - `ContributionService.book(UUID familieId, BigDecimal betrag, LocalDate eingang, List<YearMonth> monate /*≥1*/, String notiz)` — speichert `Beitrag`, auditiert.
  - `GridService.grid(YearMonth von, YearMonth bis, LocalDate heute): List<FamilyRow{familieId,name,bewohnerKuerzel,cells: Map<YearMonth,Cell{soll, bezahlt, status}>, guthaben}>`; `Status{BEZAHLT,TEILWEISE,OFFEN,UEBERFAELLIG}`.
  - Verteilungsregel (verbindlich): Alle Zahlungen einer Familie werden chronologisch nach `eingangsdatum` auf die **ältesten noch offenen** Monate (aus dem Raster ab dem Eintrittsmonat/erstem Sollbetrag) verteilt, nicht auf die vom Kassenverwalter angegebenen Monate; die `monate`-Angabe dient der Anzeige/Notiz. Überschuss über den letzten angezeigten Monat hinaus = `guthaben`. `UEBERFAELLIG`, wenn nicht voll bezahlt und `heute > faelligkeitstag` des Monats; `OFFEN` sonst; `TEILWEISE`, wenn 0 < bezahlt < soll und noch nicht überfällig (überfällig hat Vorrang).
  - REST (KV): `GET/POST/PUT /api/families`, `POST /api/families/{id}/sollbetrag`, `POST /api/contributions`, `GET /api/contributions?familie`, `GET /api/contributions/grid?von&bis`.

- [ ] **Step 1:** Failing Tests für `GridServiceTest` (feste „heute“-Uhr per Parameter): (a) Soll 50, Zahlung 50 für Jan → Jan BEZAHLT; (b) Zahlung 100 am 05.01. → Jan+Feb BEZAHLT; (c) Zahlung 30 → TEILWEISE; (d) kein Eingang, heute nach Fälligkeit → UEBERFAELLIG, davor OFFEN; (e) Sollbetrag-Änderung ab März (60) lässt Jan/Feb bei 50; (f) Zahlung 200 bei Soll 50 über 3 Monate Anzeige → 3 bezahlt, 50 Guthaben; (g) Ältester offener Monat wird zuerst gefüllt, auch wenn Notiz „März“ sagt.
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: families, contributions and payment grid`.

---

### Task 12: Belegerkennung per KI (Spring AI)

**Files:**
- Create: `receipt/ReceiptRecognitionService.java`, `receipt/ReceiptSuggestion.java` (record), `receipt/ReceiptPlausibility.java`, `receipt/ReceiptController.java`, `receipt/ReceiptAiProperties.java`
- Test: `receipt/ReceiptPlausibilityTest.java`, `receipt/ReceiptRecognitionServiceTest.java` (ChatClient gemockt)

**Interfaces:**
- Consumes: `CategoryService.visible()`, `BalanceService`.
- Produces:
  - `record ReceiptSuggestion(String haendler, LocalDate datum, BigDecimal betrag, UUID kategorieId)` (alle nullable).
  - `ReceiptRecognitionService.recognize(MultipartFile foto): Optional<ReceiptSuggestion>` — leer, wenn `app.receipt.enabled=false` oder bei Fehler/Timeout (10 s). Ablauf: Modell `app.receipt.model` (Default `gpt-6-luna`, konfigurierbar) mit festem JSON-Schema via `.entity(ReceiptDto.class)`; Prompt enthält die sichtbaren Kategorienamen; Mapping Kategorienname → ID; `ReceiptPlausibility.isPlausible(s, today, kassenstand)` (Datum ≤ heute, 0 < Betrag, Pflichtfelder gesetzt, Betrag < Kassenstand) → sonst genau ein zweiter Versuch mit `app.receipt.fallback-model` (Default `gpt-6.1-sol`); danach Rückgabe des besten Ergebnisses (auch unplausibel, UI zeigt es zur Korrektur).
  - REST `POST /api/receipts/recognize` (multipart `photo`, aktive Mitglieder) → `ReceiptSuggestion` oder `204`.
- Konfiguration: `app.receipt.enabled` (Default `false`), `OPENAI_API_KEY`. Das Foto wird nicht gespeichert.

- [ ] **Step 1:** Failing Tests: Plausibilität (Zukunftsdatum, Betrag 0, Betrag ≥ Kassenstand, fehlendes Feld → false; gültiger Fall → true); Service: erstes Modell plausibel → ein Aufruf; unplausibel → zweiter Aufruf mit Fallback-Modell; Exception → `Optional.empty()`; `enabled=false` → leer ohne Aufruf; Kategoriename wird auf ID gemappt, unbekannte Kategorie → `null`.
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: ai receipt recognition`.

---

### Task 13: Benachrichtigungen (Kassenwarnung, Web-Push, Erinnerungs-Mail)

**Files:**
- Create: `notify/LowBalanceMonitor.java`, `notify/PushService.java`, `notify/PushController.java`, `notify/ReminderService.java`, `notify/VapidProperties.java`
- Modify: `ledger/LedgerService.java`, `expense/ExpenseService.java` (Hook nach Buchung), `db/migration/V2__low_balance_flag.sql` (Spalte `wohngruppe.warnung_aktiv boolean not null default false`)
- Test: `notify/LowBalanceMonitorTest.java`, `notify/ReminderServiceTest.java`

**Interfaces:**
- Produces:
  - `LowBalanceMonitor.afterBooking()` — wird nach jeder Barkassen-Buchung aufgerufen. Fällt der Stand unter `mindestbetragKasse` und `warnung_aktiv=false` ⇒ Mail + Push an alle aktiven Kassenverwalter der Gruppe, `warnung_aktiv=true`. Steigt der Stand wieder auf ≥ Mindestbetrag (durch Auffüllung), `warnung_aktiv=false`.
  - `PushService.sendTo(UUID personId, String title, String body)` (webpush-java, VAPID aus `VAPID_PUBLIC`, `VAPID_PRIVATE`, `VAPID_SUBJECT`; 410/404 → Subscription löschen).
  - REST: `GET /api/push/public-key`, `POST /api/push/subscribe {endpoint,p256dh,auth}`, `DELETE /api/push/subscribe`.
  - `ReminderService.sendOverdueReminders(): int` (Anzahl Mails) — an `kontakt_email` aller Familien mit überfälligen Monaten laut `GridService`; REST `POST /api/contributions/reminders` (KV), Rückgabe `{gesendet}`; Familien ohne E-Mail werden übersprungen.

- [ ] **Step 1:** Failing Tests: Stand 60 → Ausgabe 20 (Mindest 50) ⇒ genau eine Mail je KV; weitere Ausgabe (Stand 10) ⇒ keine zweite; nach Auffüllung über 50 ⇒ Flag zurückgesetzt, nächste Unterschreitung benachrichtigt wieder; Reminder: zwei Familien überfällig, eine ohne E-Mail ⇒ 1 Mail.
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: low balance warning, push and reminders`.

---

### Task 14: Auswertungen und Dashboards (Backend)

**Files:**
- Create: `reports/ReportService.java`, `reports/ReportController.java`, `reports/dto/*.java`
- Test: `reports/ReportServiceTest.java`

**Interfaces:**
- Produces REST (KV; Pflegekraft nur `/api/dashboard/caregiver`):
  - `GET /api/dashboard/manager → {barkasse, vereinskonto, eingereichtAnzahl, offeneBeitraege:{offen,ueberfaellig}, letzteBestandserfassung:Instant|null}`
  - `GET /api/dashboard/caregiver → {barkasse, warnung, eigeneAusgaben30Tage:[ExpenseDto]}`
  - `GET /api/reports/expenses-by-category-month?von&bis&person&bewohner&kategorie → [{monat, kategorieName, summe}]` (Ausgaben ohne stornierte; positive Beträge)
  - `GET /api/reports/income-vs-expense?von&bis → [{monat, einnahmen /*Beiträge*/, ausgaben /*Ausgaben + Kontoausgaben*/}]`
  - `GET /api/reports/balance-history?von&bis&konto → [{datum, stand}]` (kumuliert aus Anfangsbestand beginnend)
- Regeln: Stornierte Original- und Stornobuchungen heben sich in Summen auf (Storno wird nicht als Einnahme gezählt: Auswertungen filtern `status<>STORNIERT and art<>STORNO`).

- [ ] **Step 1:** Failing Tests mit festen Buchungen: Summen je Kategorie/Monat, Filter nach Person/Bewohner, Stornos ausgeschlossen, Verlauf kumuliert korrekt, Pflegekraft auf `/api/reports/*` → 403.
- [ ] **Step 2–4:** FAIL → implementieren (JPQL/Aggregation im Repository) → PASS. **Step 5:** Commit `feat: dashboards and reports`.

---

### Task 15: Export (PDF, CSV, ZIP)

**Files:**
- Create: `export/ExportController.java`, `export/AnnualReportPdfService.java`, `export/CsvExportService.java`, `export/ReceiptZipService.java`
- Test: `export/CsvExportTest.java`, `export/ReceiptZipTest.java`, `export/AnnualReportPdfTest.java`

**Interfaces:**
- Produces REST (nur KV/Admin): `GET /api/export/annual-report.pdf?jahr`, `GET /api/export/bookings.csv?jahr`, `GET /api/export/receipts.zip?jahr`.
  - CSV: UTF-8 mit BOM, Trennzeichen `;`, Dezimalkomma, Spalten `Nummer;Datum;Konto;Art;Betrag;Kategorie;Zweck;Händler;Bewohner;Status;Erfasst von;Erfasst am;Storno von`; CSV-Injection-Schutz (Werte mit `=,+,-,@` am Anfang mit `'` prefixen).
  - ZIP: Dateiname `{yyyy-MM-dd}_{nummer}_{index}.{ext}` je Foto.
  - PDF (OpenPDF): Einnahmen (Beiträge je Familie/Monat Summe), Ausgaben nach Kategorie, Kassenbuch (alle Buchungen des Jahres, Anfangs- und Endstand je Konto). Streaming, kein Zwischenspeichern ganzer Dateien im RAM über 50 MB.

- [ ] **Step 1:** Failing Tests: CSV enthält Header, Dezimalkomma, injizierte Zelle `=SUM(1)` wird entschärft; ZIP-Eintragsnamen stimmen; PDF-Bytes beginnen mit `%PDF` und enthalten (per Text-Extraktion) Gruppenname und Endstand. Pflegekraft → 403.
- [ ] **Step 2–4:** FAIL → implementieren → PASS. **Step 5:** Commit `feat: exports`.

---

### Task 16: OpenAPI-Vertrag

**Files:**
- Create: `src/main/resources/application.yml` Ergänzung (`springdoc.api-docs.path=/api-docs`), `scripts/export-openapi.sh`, `frontend/openapi.json` (generiert)
- Test: `OpenApiTest.java` (`GET /api-docs` enthält `/api/expenses`)

- [ ] **Step 1:** Failing Test → **Step 2:** FAIL → **Step 3:** springdoc konfigurieren, `/api-docs` ohne Login erreichbar nur im Profil `dev`; `scripts/export-openapi.sh` startet den Test-Context bzw. ruft `mvn -q spring-boot:run -Dspring-boot.run.profiles=dev` und schreibt `frontend/openapi.json`. → **Step 4:** PASS. **Step 5:** Commit `chore: openapi contract`.

---

### Task 17: Frontend-Gerüst (Vite, Mantine, orval, Auth, Layout)

**Files:**
- Create: `frontend/package.json`, `vite.config.ts`, `tsconfig.json`, `orval.config.ts`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/api/mutator.ts`, `src/api/generated/` (orval), `src/auth/AuthProvider.tsx`, `src/auth/RequireRole.tsx`, `src/layout/AppShell.tsx`, `src/layout/GroupSwitcher.tsx`, `src/pages/LoginPage.tsx`, `RegisterPage.tsx`, `PasswordResetPages.tsx`, `InvitationPage.tsx`, `src/format.ts` (EUR, Datum de-DE)
- Test: `frontend/src/auth/RequireRole.test.tsx`, `src/format.test.ts` (Vitest + Testing Library)

**Interfaces:**
- Consumes: REST aus Task 4–6.
- Produces: `useMe(): {me, isLoading}`, `<RequireRole roles={...}>` (Rollen: `admin|kassenverwalter|pflegekraft` relativ zur aktiven Gruppe), `formatEuro(n: number): string` (`1.234,50 €`), `api` (orval-Client mit `mutator` der `credentials:'include'` setzt und das `XSRF-TOKEN`-Cookie als Header `X-XSRF-TOKEN` mitsendet). Dev-Proxy `/api` → `http://localhost:8080`.
- Routing: `/login`, `/registrieren`, `/passwort-vergessen`, `/passwort-neu/:token`, `/einladung/:token`, `/` → je nach Rolle Startseite. Wartende/gesperrte Nutzer sehen eine Hinweisseite. Datenschutzhinweis mit Pflicht-Checkbox bei Registrierung. Mantine `MantineProvider` mit Dark-Mode-Umschalter.

- [ ] **Step 1:** `npm create vite@latest frontend -- --template react-ts`, Abhängigkeiten installieren (`@mantine/core @mantine/hooks @mantine/form @mantine/dates @mantine/charts @mantine/notifications @tanstack/react-query react-router-dom zod mantine-form-zod-resolver dayjs recharts`; Dev: `orval vitest @testing-library/react jsdom vite-plugin-pwa`).
- [ ] **Step 2:** Failing Tests (`formatEuro(1234.5) === '1.234,50 €'`; `RequireRole` rendert Kinder nur bei passender Rolle). **Step 3:** FAIL → implementieren. **Step 4:** `npm test` PASS; `npm run build` ohne Fehler.
- [ ] **Step 5:** `npx orval` erzeugt Client aus `frontend/openapi.json`; Commit `feat: frontend scaffold and auth pages`.

---

### Task 18: Frontend — Pflegekraft-Oberfläche (Belegerfassung)

**Files:**
- Create: `src/pages/caregiver/HomePage.tsx`, `ExpenseCapturePage.tsx`, `MyExpensesPage.tsx`, `ExpenseEditPage.tsx` (Korrektur abgelehnter), `src/image/resizeImage.ts`, `src/components/StatusBadge.tsx`
- Test: `src/image/resizeImage.test.ts`, `src/pages/caregiver/ExpenseCapturePage.test.tsx`

**Interfaces:**
- Consumes: `POST /api/receipts/recognize`, `POST /api/expenses`, `PUT /api/expenses/{id}`, `GET /api/dashboard/caregiver`, `GET /api/categories`.
- Produces: `resizeImage(file: File, maxWidth = 1500): Promise<Blob>` — Canvas, WebP wenn `canvas.toBlob(..., 'image/webp')` den Typ `image/webp` liefert, sonst JPEG (Qualität 0.8); `<input type="file" accept="image/*" capture="environment" multiple>`.
- Startseite: großer Button „Beleg erfassen“, Kassenstand, Warnhinweis (wenn `warnung`), eigene Ausgaben der letzten 30 Tage mit Status. Erfassungs-Flow: Foto(s) → verkleinern → Vorschläge laden (Ladeindikator, bei Fehler Felder leer) → Formular (Betrag, Datum, Kategorie, Händler, Zweck, Bewohner) → Absenden. Ohne Foto: Link „Ohne Beleg erfassen“ mit Pflichtbegründung. Abgelehnte Ausgaben zeigen den Kommentar und führen zur Bearbeitung.
- Zod-Schema: Betrag > 0, Datum ≤ heute, Kategorie Pflicht, Zweck optional; Foto oder Begründung Pflicht.

- [ ] **Step 1:** Failing Tests: `resizeImage` skaliert 3000×2000 auf Breite 1500 (Canvas gemockt) und bleibt bei 1000 Breite unverändert; Formular verhindert Absenden ohne Foto und ohne Begründung.
- [ ] **Step 2–4:** FAIL → implementieren → PASS (`npm test`, `npm run build`). **Step 5:** Commit `feat: caregiver ui`.

---

### Task 19: Frontend — Kassenverwalter- und Admin-Oberfläche

**Files:**
- Create: `src/pages/manager/DashboardPage.tsx`, `ReviewListPage.tsx`, `ExpenseDetailDrawer.tsx`, `CashPage.tsx` (Bestand erfassen, Auffüllung, Rückgabe, Korrektur, Kontoausgabe, Anfangsbestand, Kassenbuch mit Storno), `ContributionsPage.tsx` (Familien, Sollbeträge, Zahlungseingang, Raster, Erinnerungen), `ReportsPage.tsx` (gestapelte Balken, Einnahmen/Ausgaben, Stand-Verlauf mit `@mantine/charts`, Filter), `MembersPage.tsx`, `SettingsPage.tsx` (Mindestbetrag, Beitrittscode, Kategorien), `ExportPage.tsx`, `src/pages/admin/AdminGroupsPage.tsx`
- Test: `src/pages/manager/CashPage.test.tsx`, `src/pages/manager/ContributionsGrid.test.tsx`

**Interfaces:**
- Consumes: alle KV-Endpunkte aus Tasks 7–15.
- UI-Regeln: Startseite beantwortet „Wie viel in der Kasse / was ist zu prüfen / wer hat nicht gezahlt“ plus Datum der letzten Bestandserfassung. „Bestand erfassen“ zeigt Soll, Ist (Eingabe), Differenz und verlangt bei Differenz eine Begründung. Raster: Farben für bezahlt/teilweise/offen/überfällig (zusätzlich Text/Icon, nicht nur Farbe). Negativer Kassenstand rot mit Warnhinweis. Prüfliste hebt Ausgaben „ohne Beleg“ hervor. Belegbilder werden über `/api/receipts/...` geladen. Admin: Wohngruppen anlegen/auflisten, Kassenverwalter einladen.
- Mobil nutzbar (Mantine `AppShell` mit Burger-Navigation).

- [ ] **Step 1:** Failing Tests: `CashPage` verlangt Begründung bei Differenz ≠ 0, nicht bei 0; Raster rendert Status-Text je Zelle.
- [ ] **Step 2–4:** FAIL → implementieren → PASS (`npm test`, `npm run build`). **Step 5:** Commit `feat: manager and admin ui`.

---

### Task 20: PWA und Web-Push im Frontend

**Files:**
- Modify: `frontend/vite.config.ts` (vite-plugin-pwa, `injectManifest` mit eigenem `src/sw.ts`), Create: `src/sw.ts` (Precache, `push`-Event → `showNotification`, `notificationclick` → öffnet `/`), `public/icons/*` (192/512 PNG), `src/push/usePush.ts`, Einstellungs-Schalter „Benachrichtigungen aktivieren“
- Test: `src/push/usePush.test.ts`

**Interfaces:**
- Consumes: `GET /api/push/public-key`, `POST /api/push/subscribe`.
- Produces: `usePush(): {supported, subscribed, subscribe(): Promise<void>, unsubscribe(): Promise<void>}`. Manifest: Name „Gruppenkasse“, `display: standalone`, `lang: de`, Theme-Farbe. API-Antworten (`/api/**`) werden nicht vom Service Worker gecacht.

- [ ] **Step 1:** Failing Test (`supported=false` ohne `PushManager`; `subscribe` ruft API mit Schlüsseln). **Step 2–4:** FAIL → implementieren → PASS, `npm run build` erzeugt Manifest und SW. **Step 5:** Commit `feat: pwa and push client`.

---

### Task 21: Docker, Compose, Caddy, Betriebsskripte

**Files:**
- Create: `Dockerfile`, `deploy/docker-compose.yml`, `deploy/Caddyfile`, `deploy/postgres.conf`, `deploy/setup.sh`, `deploy/env.example`, `docs/betrieb.md` (erweitern)
- Test: `scripts/smoke.sh` (lokaler Compose-Test)

**Interfaces:**
- Dockerfile mehrstufig: (1) `node:22` baut `frontend` → `dist`; (2) `maven:3-eclipse-temurin-25` kopiert `dist` nach `src/main/resources/static`, `mvn -DskipTests package`; (3) schlankes JRE 25, Start `java -Xmx512m -XX:+UseSerialGC -jar app.jar`, Nutzer ohne Root, Volume `/data/receipts`.
- Compose: Services `caddy` (Ports 80/443, Volume `/data`, Caddyfile mit `{$DOMAIN}`), `app` (Image `ghcr.io/${GITHUB_REPOSITORY}:${IMAGE_TAG}`, `depends_on: db healthy`, Healthcheck `/actuator/health`, `mem_limit: 700m`), `db` (`postgres:17`, `shared_buffers=128MB`, `max_connections=20`, Volume). Nur Caddy veröffentlicht Ports. Caddyfile wie in der Spezifikation (`reverse_proxy app:8080`, `encode gzip`, `request_body max_size 20MB`).
- `setup.sh` (idempotent): Docker + Compose installieren, `ufw` nur 22/80/443, SSH nur per Schlüssel (`PermitRootLogin prohibit-password`, `PasswordAuthentication no`), 1-GB-Swap, `unattended-upgrades`, Deploy-Benutzer `deploy` mit Docker-Gruppe, Verzeichnis `/opt/gruppenkasse`.
- `env.example`: `DOMAIN`, `DB_PASSWORD`, `OPENAI_API_KEY`, `VAPID_*`, `SMTP_*`, `APP_BASE_URL`, `IMAGE_TAG`, `GITHUB_REPOSITORY`.

- [ ] **Step 1:** `docker build .` lokal → muss durchlaufen. **Step 2:** `docker compose -f deploy/docker-compose.yml up -d` mit Test-`.env` (Domain `localhost`, Caddy `tls internal`), `curl -k https://localhost/actuator/health` → `UP`. **Step 3:** `shellcheck deploy/setup.sh` ohne Fehler. **Step 4:** Commit `build: docker, compose, caddy, server setup`.

---

### Task 22: CI/CD (GitHub Actions), Rollback, Dependabot

**Files:**
- Create: `.github/workflows/ci-deploy.yml`, `.github/workflows/rollback.yml`, `.github/dependabot.yml`
- Test: `act`/Syntax: `actionlint`

**Interfaces:**
- `ci-deploy.yml`: Trigger `push`. Job `test` (Java 25, Node 22, `./mvnw verify`, `npm ci && npm test && npm run build` in `frontend`). Bei `main`: Job `deploy` baut Image, pusht nach GHCR mit Tag `${{ github.sha }}`, kopiert `deploy/docker-compose.yml` + `deploy/Caddyfile` + `deploy/postgres.conf` per SCP, schreibt `.env` aus Secrets (`DOMAIN, DB_PASSWORD, OPENAI_API_KEY, VAPID_PUBLIC, VAPID_PRIVATE, VAPID_SUBJECT, SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, APP_BASE_URL` plus `IMAGE_TAG`), merkt den vorigen Tag (`.env.prev`), führt `docker compose pull && docker compose up -d` und `docker image prune -f` aus, pollt `https://$DOMAIN/actuator/health` bis 2 Minuten; bei Misserfolg setzt es `IMAGE_TAG` auf den vorigen Wert und startet neu und lässt den Job fehlschlagen. SSH-Zugang über Secrets `SSH_HOST, SSH_USER, SSH_KEY`.
- `rollback.yml`: `workflow_dispatch` mit Input `image_tag`; setzt den Tag auf dem Server, startet neu, prüft Health.
- `dependabot.yml`: wöchentlich für `maven`, `npm` (Ordner `frontend`), `docker`, `github-actions`.

- [ ] **Step 1:** Workflows schreiben. **Step 2:** `actionlint` ohne Fehler. **Step 3:** Commit `ci: build, deploy, rollback, dependabot`.

---

### Task 23: Abschluss — Gesamttest, Doku, Spec-Abgleich

**Files:**
- Create: `README.md` (Überblick, lokale Entwicklung, Betrieb, initialer Admin per SQL, Secrets-Liste), `src/test/java/de/grouppurse/EndToEndScenarioTest.java`

- [ ] **Step 1:** Szenariotest (alles über REST mit Testcontainers): Admin legt Gruppe an, lädt KV ein, KV löst ein, setzt Anfangsbestände (Konto 1000, Barkasse 100), Pflegekraft registriert sich mit Code, KV schaltet frei, Pflegekraft reicht Ausgabe 30 € ein (Barkasse 70), KV lehnt ab, Pflegekraft reicht neu ein, KV prüft; Auffüllung 100 (Barkasse 170, Konto 900); Beitrag gebucht (Konto 950, Raster grün); eine zweite Gruppe sieht nichts davon; Pflegekraft der Gruppe A kann Gruppe B nicht wählen; CSV-Export enthält alle Buchungen.
- [ ] **Step 2:** `./mvnw verify` und `cd frontend && npm test && npm run build` → alles grün.
- [ ] **Step 3:** Spec-Abgleich (Abschnitt für Abschnitt prüfen, Lücken als Issues notieren). **Step 4:** Commit `docs: readme and e2e scenario`.

---

## Self-Review (Spec-Abdeckung)

- Rollen/Rechte inkl. Admin → Tasks 4–6; Mandantentrennung/Beitrittscode/mehrere Gruppen → 3, 4, 5, 6; Kategorien → 8.
- Geldfluss, Elternbeiträge, Raster, Erinnerung → 11, 13; Kontoausgabe → 10; Belegerfassung/Status/ohne Foto → 9; KI → 12.
- Barkasse (Buchungsarten, Storno, Bestand erfassen statt Kassensturz, Warnung) → 7, 13.
- Startseiten, Auswertungen, Export → 14, 15, 18, 19.
- Datenmodell + Ergänzungen → 2; Audit → 7; Datenschutz-Zustimmung → 4/6/17.
- PWA/Push → 13, 20; Hosting/CI/Rollback/Setup → 21, 22.
- Bewusst nicht enthalten (laut Spec): Bankabgleich, Auslagen-Erstattung, Gruppenkarten, Bewohner-Bargeld, Backups, Staging.
- Offene externe Punkte (nicht im Code lösbar): Modell `gpt-6-luna` Bildunterstützung prüfen, AVV mit OpenAI/Strato, Test mit echten Bons, Aufbewahrungsfrist, Domain/Strato-Bestellung, GitHub-Repo und Secrets.

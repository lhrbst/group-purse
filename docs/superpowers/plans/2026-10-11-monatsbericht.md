# Monatsübersicht und PDF-Monatsbericht Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Kassenverwalter sieht die Ausgaben eines Monats als Text und Tabellen auf der Seite *Auswertungen* und lädt denselben Bericht als PDF herunter.

**Architecture:** Ein neuer `MonthlyReportService` berechnet den Monatsbericht einmal (Records in `ReportDtos`). `ReportController` liefert ihn als JSON (`/api/reports/monthly`) und, über `MonthlyReportPdf` (OpenPDF), als PDF (`/api/reports/monthly.pdf`). Das Frontend zeigt das JSON in einer neuen Komponente `MonthlyOverview` und verlinkt das PDF mit einem gewöhnlichen Link (Cookie-Sitzung).

**Tech Stack:** Spring Boot 4.1, JPA/Specifications, OpenPDF 3.0.5 (`org.openpdf.*`), Micrometer, React 19, Mantine 9, orval-Client, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-11-monatsbericht-design.md` (Hauptspezifikation: `docs/Spezifikation Web-App Gruppenkasse für Wohngruppen.md`, Abschnitt „Zurückgestellt“ ist angepasst).

## Global Constraints

- Nur der Kassenverwalter der aktiven Gruppe (`access.requireKassenverwalter()`), andere bekommen 403; alle Daten sind auf die Gruppe beschränkt (Hibernate-Mandantenfilter).
- Zahlenbasis: `Art` AUSGABE und KONTOAUSGABE, Belegdatum im Monat, nur Status `GEBUCHT`, `EINGEREICHT`, `GEPRUEFT`. `STORNIERT`, `ABGELEHNT`, `VERARBEITUNG`, `MANUELL` zählen nicht und werden **nirgends erwähnt**. `EINGEREICHT` zählt mit und ist als „zu prüfen“ markiert.
- Beträge aus den Belegpositionen (`buchung_position`), als positive Ausgaben.
- Beträge deutsch (`1.234,56 €`), Datum `TT.MM.JJJJ`, DIN A4 hoch, Seitenzahlen, wiederholte Tabellenköpfe, Fußzeile „Erstellt am … von …“.
- Zeichen außerhalb von Windows-1252 werden im PDF durch „?“ ersetzt (Erzeugung scheitert nie daran).
- `GET /api/reports/monthly?monat=YYYY-MM` (JSON) und `GET /api/reports/monthly.pdf?monat=YYYY-MM` mit `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="Gruppenkasse-YYYY-MM.pdf"`, `Cache-Control: no-store`. Ungültiges Format oder Zukunftsmonat → 400, der laufende Monat ist erlaubt.
- Das PDF wird bei jedem Aufruf neu erzeugt, nichts wird gespeichert. Kein Audit-Eintrag.
- Metrik `gk_report_pdf_total` (Micrometer `gk.report.pdf`) mit Label `outcome` (`ok`, `error`); keine Beträge, keine Personen, keine Wohngruppen.
- Oberfläche nur auf Deutsch. Kein CSV, kein Beleg-ZIP, kein Jahresabschluss, kein Versand.
- Code-Stil wie im Bestand: öffentliche Felder in Entitäten, Records als DTOs, deutsche Kommentare/Meldungen, wenige Kommentare.

## Review Focus

Eingabeklassen, die die Spec impliziert und die nicht offensichtlich getestet wären (jede Zeile hat einen Test in der genannten Task):

1. Eine Ausgabe **ohne Positionen** (Altdaten) muss mit ihrem Buchungsbetrag zählen, nicht als 0 (Task 1: `bookingWithoutPositionsUsesItsAmount`).
2. **Gesamtsumme 0** bei vorhandenen Belegen (z. B. Ausgabe über 0,00 €) darf nicht durch 0 teilen (Task 1: `zeroTotalDoesNotDivideByZero`).
3. **Monatsgrenzen**: 1. und letzter Tag zählen, Vor- und Folgemonat nicht, auch der 29.02. im Schaltjahr (Task 1: `monthBoundariesIncludeFirstAndLastDay`, `leapDayBelongsToFebruary`).
4. **Ungewöhnlicher Text** in Händler und Namen (Emoji, griechische Buchstaben, 300 Zeichen ohne Leerzeichen, Zeilenumbrüche, kein Händler) bricht die PDF-Erzeugung nicht ab (Task 2: `awkwardTextNeverBreaksRendering`, `latinReplacesUnsupportedCharacters`).
5. **Großer Monat** (über 100 Belege): Seitenumbruch, wiederholter Tabellenkopf, richtige Seitenzahl (Task 2: `longListBreaksPagesAndRepeatsHeader`). Dazu die **Rundung** der Anteile (drei gleiche Kategorien ergeben je 33,3 %; die Summenzeile bleibt exakt) in Task 1: `sharesAreRoundedPerRowAndTotalStaysExact`.

---

### Task 1: MonthlyReportService (Zahlen, Text, Formatierung)

**Files:**
- Create: `src/main/java/de/grouppurse/reports/GermanFormat.java`
- Create: `src/main/java/de/grouppurse/reports/MonthlyReportService.java`
- Modify: `src/main/java/de/grouppurse/reports/ReportDtos.java`
- Test: `src/test/java/de/grouppurse/reports/GermanFormatTest.java`
- Test: `src/test/java/de/grouppurse/reports/MonthlyReportServiceTest.java`

**Interfaces:**
- Consumes: `CategoryService.tree()` → `CategoryTree` (`name(UUID)`, `mainId(UUID)`, `mainName(UUID)`), `BuchungRepository` (`JpaSpecificationExecutor`), `BuchungPositionRepository.findByBuchungIdInOrderByPositionAsc(Collection<UUID>)`, `PersonRepository`, `WohngruppeRepository`, `TenantContext.get()`.
- Produces:
  - Records in `ReportDtos`: `MonthlyReport(String monat, String gruppe, BigDecimal summe, int belegAnzahl, BigDecimal vormonatSumme, BigDecimal aenderung, BigDecimal aenderungProzent, int ungeprueftAnzahl, BigDecimal ungeprueftSumme, String zusammenfassung, List<MonthlyCategory> kategorien, List<MonthlyBeleg> belege)`, `MonthlyCategory(String hauptkategorie, String unterkategorie, BigDecimal betrag, BigDecimal anteilProzent)`, `MonthlyBeleg(LocalDate datum, long nummer, Konto konto, String haendler, String erfasstVon, BigDecimal betrag, boolean zuPruefen, List<MonthlyPosition> positionen)`, `MonthlyPosition(String kategorie, BigDecimal betrag)`.
  - `MonthlyReportService.report(YearMonth monat)` → `MonthlyReport`.
  - `GermanFormat` (package-private): `money(BigDecimal)`, `signedMoney(BigDecimal)`, `percent(BigDecimal)`, `signedPercent(BigDecimal)`, `date(LocalDate)`, `month(YearMonth)`.

- [ ] **Step 1: Write the failing format test**

`src/test/java/de/grouppurse/reports/GermanFormatTest.java`:

```java
package de.grouppurse.reports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class GermanFormatTest {
    @Test
    void formatsMoneyWithGermanSeparators() {
        assertThat(GermanFormat.money(new BigDecimal("1234.5"))).isEqualTo("1.234,50 €");
        assertThat(GermanFormat.money(new BigDecimal("0"))).isEqualTo("0,00 €");
        assertThat(GermanFormat.money(new BigDecimal("-7.005"))).isEqualTo("-7,01 €");
    }

    @Test
    void signedValuesCarryAnExplicitPlus() {
        assertThat(GermanFormat.signedMoney(new BigDecimal("93"))).isEqualTo("+93,00 €");
        assertThat(GermanFormat.signedMoney(new BigDecimal("-93"))).isEqualTo("-93,00 €");
        assertThat(GermanFormat.signedMoney(BigDecimal.ZERO)).isEqualTo("0,00 €");
        assertThat(GermanFormat.signedPercent(new BigDecimal("1328.6"))).isEqualTo("+1.328,6 %");
        assertThat(GermanFormat.percent(new BigDecimal("46.2"))).isEqualTo("46,2 %");
    }

    @Test
    void formatsDatesAndMonthNamesInGerman() {
        assertThat(GermanFormat.date(LocalDate.of(2026, 3, 4))).isEqualTo("04.03.2026");
        assertThat(GermanFormat.month(YearMonth.of(2026, 3))).isEqualTo("März 2026");
        assertThat(GermanFormat.month(YearMonth.of(2026, 10))).isEqualTo("Oktober 2026");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=GermanFormatTest`
Expected: compilation error, `GermanFormat` does not exist.

- [ ] **Step 3: Write `GermanFormat`**

`src/main/java/de/grouppurse/reports/GermanFormat.java`:

```java
package de.grouppurse.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Deutsche Darstellung von Beträgen, Prozenten, Daten und Monaten für Bericht und PDF. */
final class GermanFormat {
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.GERMANY);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private GermanFormat() {
    }

    static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.00", SYMBOLS).format(value.setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    static String signedMoney(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + money(value);
    }

    static String percent(BigDecimal value) {
        return new DecimalFormat("#,##0.0", SYMBOLS).format(value) + " %";
    }

    static String signedPercent(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + percent(value);
    }

    static String date(LocalDate date) {
        return DATE.format(date);
    }

    static String month(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.GERMAN) + " " + month.getYear();
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=GermanFormatTest`
Expected: 3 tests pass. (`-7.005` rundet bei `HALF_UP` auf `-7,01`.)

- [ ] **Step 5: Add the DTO records**

In `src/main/java/de/grouppurse/reports/ReportDtos.java` den Import `de.grouppurse.domain.Konto` ergänzen und vor der schließenden Klammer einfügen:

```java
    public record MonthlyReport(String monat, String gruppe, BigDecimal summe, int belegAnzahl, BigDecimal vormonatSumme,
                                BigDecimal aenderung, BigDecimal aenderungProzent, int ungeprueftAnzahl, BigDecimal ungeprueftSumme,
                                String zusammenfassung, List<MonthlyCategory> kategorien, List<MonthlyBeleg> belege) {
    }

    /** unterkategorie ist null, wenn die Positionen direkt auf der Hauptkategorie liegen. anteilProzent ist null-sicher (0,0 bei Summe 0). */
    public record MonthlyCategory(String hauptkategorie, String unterkategorie, BigDecimal betrag, BigDecimal anteilProzent) {
    }

    public record MonthlyBeleg(LocalDate datum, long nummer, Konto konto, String haendler, String erfasstVon, BigDecimal betrag,
                               boolean zuPruefen, List<MonthlyPosition> positionen) {
    }

    public record MonthlyPosition(String kategorie, BigDecimal betrag) {
    }
```

- [ ] **Step 6: Write the failing service test**

`src/test/java/de/grouppurse/reports/MonthlyReportServiceTest.java`:

```java
package de.grouppurse.reports;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.repo.BuchungRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyReportServiceTest extends AbstractIntegrationTest {
    @Autowired MonthlyReportService service;
    @Autowired BuchungRepository buchungen;
    @Autowired BuchungPositionRepository positions;
    @Autowired CategoryService categories;

    static final YearMonth OKT = YearMonth.of(2026, 10);

    Wohngruppe g;
    Person kv;
    Person pk;
    UUID obst;      // Unterkategorie von "Lebensmittel"
    UUID haushalt;  // Hauptkategorie
    long nr;

    @BeforeEach
    void setUp() {
        g = t.group("Monat");
        kv = t.person("kv", false);
        pk = t.person("pk", false);
        t.member(kv, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        t.member(pk, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        t.as(kv, g, () -> {
            obst = categories.visible().stream().filter(c -> c.name().equals("Obst und Gemüse")).findFirst().orElseThrow().id();
            haushalt = categories.visible().stream().filter(c -> c.name().equals("Haushalt")).findFirst().orElseThrow().id();
        });
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** Legt direkt eine Buchung mit Positionen an; parts sind abwechselnd Kategorie-ID und Betrag (String). */
    Buchung add(Wohngruppe group, Art art, BuchungStatus status, Konto konto, String date, String haendler, Person by, Object... parts) {
        return t.as(by, group, () -> {
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 1; i < parts.length; i += 2) {
                total = total.add(bd((String) parts[i]));
            }
            Buchung b = new Buchung();
            b.nummer = ++nr;
            b.konto = konto;
            b.art = art;
            b.status = status;
            b.datum = LocalDate.parse(date);
            b.haendler = haendler;
            b.erfasstVon = by.id;
            b.betrag = art == Art.AUFFUELLUNG ? total : total.negate();
            b = buchungen.save(b);
            for (int i = 0; i < parts.length; i += 2) {
                BuchungPosition p = new BuchungPosition();
                p.buchungId = b.id;
                p.kategorieId = (UUID) parts[i];
                p.betrag = bd((String) parts[i + 1]);
                p.position = i / 2;
                positions.save(p);
            }
            return b;
        });
    }

    Buchung expense(String status, String date, String haendler, Object... parts) {
        return add(g, Art.AUSGABE, BuchungStatus.valueOf(status), Konto.BARKASSE, date, haendler, pk, parts);
    }

    MonthlyReport report(YearMonth month) {
        return t.as(kv, g, () -> service.report(month));
    }

    void seedOctober() {
        expense("GEBUCHT", "2026-10-01", "REWE", obst, "20.00");
        add(g, Art.KONTOAUSGABE, BuchungStatus.GEPRUEFT, Konto.VEREINSKONTO, "2026-10-15", "Hausmeister", kv, haushalt, "60.00");
        expense("EINGEREICHT", "2026-10-31", "Kaufland", obst, "20.00");
        // zählen nicht
        expense("STORNIERT", "2026-10-10", "Storniert", haushalt, "99.00");
        expense("ABGELEHNT", "2026-10-11", "Abgelehnt", haushalt, "98.00");
        add(g, Art.AUSGABE, BuchungStatus.VERARBEITUNG, Konto.BARKASSE, "2026-10-12", null, pk);
        add(g, Art.AUSGABE, BuchungStatus.MANUELL, Konto.BARKASSE, "2026-10-13", null, pk);
        add(g, Art.AUFFUELLUNG, BuchungStatus.GEBUCHT, Konto.BARKASSE, "2026-10-14", null, kv, haushalt, "50.00");
        // andere Monate
        expense("GEBUCHT", "2026-09-30", "Vormonat", haushalt, "7.00");
        expense("GEBUCHT", "2026-11-01", "Folgemonat", haushalt, "500.00");
    }

    @Test
    void countsOnlyExpensesWithAFixedAmount() {
        seedOctober();
        MonthlyReport r = report(OKT);
        assertThat(r.monat()).isEqualTo("2026-10");
        assertThat(r.gruppe()).isEqualTo("Monat");
        assertThat(r.summe()).isEqualByComparingTo("100.00");
        assertThat(r.belegAnzahl()).isEqualTo(3);
        assertThat(r.belege()).extracting("haendler").containsExactly("REWE", "Hausmeister", "Kaufland");
        assertThat(r.belege()).extracting("zuPruefen").containsExactly(false, false, true);
        assertThat(r.belege().get(1).konto()).isEqualTo(Konto.VEREINSKONTO);
        assertThat(r.belege().get(0).erfasstVon()).isEqualTo("pk");
        assertThat(r.ungeprueftAnzahl()).isEqualTo(1);
        assertThat(r.ungeprueftSumme()).isEqualByComparingTo("20.00");
    }

    @Test
    void groupsByCategoryBiggestFirstWithShares() {
        seedOctober();
        MonthlyReport r = report(OKT);
        assertThat(r.kategorien()).hasSize(2);
        var first = r.kategorien().get(0);
        assertThat(first.hauptkategorie()).isEqualTo("Haushalt");
        assertThat(first.unterkategorie()).isNull();
        assertThat(first.betrag()).isEqualByComparingTo("60.00");
        assertThat(first.anteilProzent()).isEqualByComparingTo("60.0");
        var second = r.kategorien().get(1);
        assertThat(second.hauptkategorie()).isEqualTo("Lebensmittel");
        assertThat(second.unterkategorie()).isEqualTo("Obst und Gemüse");
        assertThat(second.betrag()).isEqualByComparingTo("40.00");
        assertThat(second.anteilProzent()).isEqualByComparingTo("40.0");
    }

    @Test
    void comparesWithThePreviousMonth() {
        seedOctober();
        MonthlyReport r = report(OKT);
        assertThat(r.vormonatSumme()).isEqualByComparingTo("7.00");
        assertThat(r.aenderung()).isEqualByComparingTo("93.00");
        assertThat(r.aenderungProzent()).isEqualByComparingTo("1328.6");
    }

    @Test
    void summaryTextNamesMonthTotalComparisonAndBiggestItems() {
        seedOctober();
        assertThat(report(OKT).zusammenfassung()).isEqualTo(
                "Im Oktober 2026 wurden 100,00 € für 3 Belege ausgegeben, 1.328,6 % mehr als im September 2026 (7,00 €). "
                        + "Die größten Posten waren Haushalt (60,00 €, 60,0 %) und Lebensmittel (40,00 €, 40,0 %).");
    }

    @Test
    void summaryHandlesAMonthAfterAnEmptyOne() {
        expense("GEBUCHT", "2026-10-05", "REWE", obst, "10.00");
        assertThat(report(OKT).zusammenfassung()).isEqualTo(
                "Im Oktober 2026 wurden 10,00 € für 1 Beleg ausgegeben; im September 2026 gab es keine Ausgaben. "
                        + "Der größte Posten war Lebensmittel (10,00 €, 100,0 %).");
        assertThat(report(OKT).aenderungProzent()).isNull();
    }

    @Test
    void anEmptyMonthSaysSoAndHasNoRows() {
        MonthlyReport r = report(OKT);
        assertThat(r.summe()).isEqualByComparingTo("0");
        assertThat(r.belegAnzahl()).isZero();
        assertThat(r.kategorien()).isEmpty();
        assertThat(r.belege()).isEmpty();
        assertThat(r.zusammenfassung()).isEqualTo("Keine Ausgaben in diesem Monat.");
        assertThat(r.ungeprueftAnzahl()).isZero();
    }

    @Test
    void aReceiptWithSeveralCategoriesIsOneLineWithItsParts() {
        expense("GEBUCHT", "2026-10-05", "REWE", haushalt, "12.00", obst, "8.00");
        MonthlyReport r = report(OKT);
        assertThat(r.belegAnzahl()).isEqualTo(1);
        var beleg = r.belege().get(0);
        assertThat(beleg.betrag()).isEqualByComparingTo("20.00");
        assertThat(beleg.positionen()).extracting("kategorie").containsExactly("Haushalt", "Obst und Gemüse");
        assertThat(r.kategorien().stream().map(ReportDtos.MonthlyCategory::betrag).toList())
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("12.00"), bd("8.00"));
    }

    @Test
    void bookingWithoutPositionsUsesItsAmount() {
        t.as(kv, g, () -> {
            Buchung b = new Buchung();
            b.nummer = ++nr;
            b.konto = Konto.BARKASSE;
            b.art = Art.AUSGABE;
            b.status = BuchungStatus.GEBUCHT;
            b.datum = LocalDate.of(2026, 10, 2);
            b.kategorieId = haushalt;
            b.erfasstVon = pk.id;
            b.betrag = bd("-15.00");
            buchungen.save(b);
        });
        MonthlyReport r = report(OKT);
        assertThat(r.summe()).isEqualByComparingTo("15.00");
        assertThat(r.kategorien().get(0).hauptkategorie()).isEqualTo("Haushalt");
        assertThat(r.belege().get(0).positionen()).hasSize(1);
    }

    @Test
    void zeroTotalDoesNotDivideByZero() {
        expense("GEBUCHT", "2026-10-02", "Gratis", haushalt, "0.00");
        MonthlyReport r = report(OKT);
        assertThat(r.belegAnzahl()).isEqualTo(1);
        assertThat(r.summe()).isEqualByComparingTo("0");
        assertThat(r.kategorien().get(0).anteilProzent()).isEqualByComparingTo("0");
        assertThat(r.zusammenfassung()).contains("1 Beleg");
    }

    @Test
    void monthBoundariesIncludeFirstAndLastDay() {
        expense("GEBUCHT", "2026-09-30", "davor", haushalt, "1.00");
        expense("GEBUCHT", "2026-10-01", "erster", haushalt, "2.00");
        expense("GEBUCHT", "2026-10-31", "letzter", haushalt, "4.00");
        expense("GEBUCHT", "2026-11-01", "danach", haushalt, "8.00");
        MonthlyReport r = report(OKT);
        assertThat(r.belege()).extracting("haendler").containsExactly("erster", "letzter");
        assertThat(r.summe()).isEqualByComparingTo("6.00");
        assertThat(r.vormonatSumme()).isEqualByComparingTo("1.00");
    }

    @Test
    void leapDayBelongsToFebruary() {
        expense("GEBUCHT", "2028-02-29", "Schalttag", haushalt, "3.00");
        expense("GEBUCHT", "2028-03-01", "März", haushalt, "5.00");
        assertThat(report(YearMonth.of(2028, 2)).belege()).extracting("haendler").containsExactly("Schalttag");
        assertThat(report(YearMonth.of(2028, 3)).vormonatSumme()).isEqualByComparingTo("3.00");
    }

    @Test
    void sharesAreRoundedPerRowAndTotalStaysExact() {
        UUID fahrten = t.as(kv, g, () -> categories.visible().stream().filter(c -> c.name().equals("Fahrten")).findFirst().orElseThrow().id());
        expense("GEBUCHT", "2026-10-02", "A", haushalt, "10.00", obst, "10.00", fahrten, "10.00");
        MonthlyReport r = report(OKT);
        assertThat(r.kategorien().stream().map(ReportDtos.MonthlyCategory::anteilProzent).toList())
                .usingElementComparator(BigDecimal::compareTo).containsExactly(bd("33.3"), bd("33.3"), bd("33.3"));
        assertThat(r.summe()).isEqualByComparingTo("30.00");
    }

    @Test
    void otherGroupsDataIsNeverIncluded() {
        Wohngruppe other = t.group("Andere");
        Person kv2 = t.person("kv2", false);
        t.member(kv2, other, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        expense("GEBUCHT", "2026-10-05", "Meins", haushalt, "10.00");
        add(other, Art.AUSGABE, BuchungStatus.GEBUCHT, Konto.BARKASSE, "2026-10-05", "Fremd", kv2, haushalt, "999.00");
        MonthlyReport r = report(OKT);
        assertThat(r.summe()).isEqualByComparingTo("10.00");
        assertThat(r.belege()).extracting("haendler").containsExactly("Meins");
    }
}
```

Hinweis: `categories.visible()` liefert die Standardkategorien der Gruppe (wie in `ReportServiceTest`). „Fahrten“ ist eine Hauptkategorie (siehe `e2e/tests/reports.spec.ts`); falls der Name in den Standarddaten anders lautet, in `V*__*.sql` nachsehen und im Test anpassen.

- [ ] **Step 7: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=MonthlyReportServiceTest`
Expected: compilation error, `MonthlyReportService` does not exist.

- [ ] **Step 8: Write `MonthlyReportService`**

`src/main/java/de/grouppurse/reports/MonthlyReportService.java`:

```java
package de.grouppurse.reports;

import de.grouppurse.category.CategoryService;
import de.grouppurse.category.CategoryTree;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.reports.ReportDtos.MonthlyBeleg;
import de.grouppurse.reports.ReportDtos.MonthlyCategory;
import de.grouppurse.reports.ReportDtos.MonthlyPosition;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Monatsbericht der Ausgaben: Zahlen, Aufschlüsselung nach Kategorie, Einzelliste und Fließtext. Grundlage für Anzeige und PDF. */
@Service
@Transactional(readOnly = true)
public class MonthlyReportService {
    /** Nur Ausgaben mit feststehendem Betrag; VERARBEITUNG, MANUELL, ABGELEHNT und STORNIERT zählen nicht. */
    private static final List<BuchungStatus> COUNTED = List.of(BuchungStatus.GEBUCHT, BuchungStatus.EINGEREICHT, BuchungStatus.GEPRUEFT);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BuchungRepository buchungen;
    private final BuchungPositionRepository positions;
    private final CategoryService categories;
    private final PersonRepository people;
    private final WohngruppeRepository gruppen;

    public MonthlyReportService(BuchungRepository buchungen, BuchungPositionRepository positions, CategoryService categories,
                                PersonRepository people, WohngruppeRepository gruppen) {
        this.buchungen = buchungen;
        this.positions = positions;
        this.categories = categories;
        this.people = people;
        this.gruppen = gruppen;
    }

    private record Part(UUID kategorieId, BigDecimal betrag) {
    }

    private record Counted(Buchung buchung, List<Part> parts, BigDecimal total) {
    }

    public MonthlyReport report(YearMonth monat) {
        CategoryTree tree = categories.tree();
        List<Counted> current = load(monat);
        BigDecimal summe = total(current);
        BigDecimal vormonat = total(load(monat.minusMonths(1)));
        BigDecimal aenderung = summe.subtract(vormonat);
        BigDecimal aenderungProzent = vormonat.signum() == 0 ? null : aenderung.multiply(HUNDRED).divide(vormonat, 1, RoundingMode.HALF_UP);
        String gruppe = gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow().name;

        Map<UUID, String> names = people.findAllById(current.stream().map(c -> c.buchung().erfasstVon).distinct().toList()).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
        List<MonthlyBeleg> belege = current.stream().map(c -> {
            Buchung b = c.buchung();
            return new MonthlyBeleg(b.datum, b.nummer, b.konto, b.haendler, names.get(b.erfasstVon), c.total(),
                    b.status == BuchungStatus.EINGEREICHT,
                    c.parts().stream().map(p -> new MonthlyPosition(tree.name(p.kategorieId()), p.betrag())).toList());
        }).toList();

        Map<UUID, BigDecimal> perCategory = new HashMap<>();
        Map<String, BigDecimal> perMain = new HashMap<>();
        for (Counted c : current) {
            for (Part p : c.parts()) {
                perCategory.merge(p.kategorieId(), p.betrag(), BigDecimal::add);
                perMain.merge(tree.mainName(p.kategorieId()), p.betrag(), BigDecimal::add);
            }
        }
        List<MonthlyCategory> kategorien = perCategory.entrySet().stream().map(e -> {
            UUID id = e.getKey();
            String sub = Objects.equals(tree.mainId(id), id) ? null : tree.name(id);
            return new MonthlyCategory(tree.mainName(id), sub, e.getValue(), share(e.getValue(), summe));
        }).sorted(Comparator.comparing(MonthlyCategory::betrag, Comparator.reverseOrder())
                .thenComparing(MonthlyCategory::hauptkategorie)
                .thenComparing(c -> c.unterkategorie() == null ? "" : c.unterkategorie())).toList();

        List<Counted> unchecked = current.stream().filter(c -> c.buchung().status == BuchungStatus.EINGEREICHT).toList();
        return new MonthlyReport(monat.toString(), gruppe, summe, current.size(), vormonat, aenderung, aenderungProzent,
                unchecked.size(), total(unchecked), summary(monat, summe, current.size(), vormonat, aenderung, aenderungProzent, perMain, tree),
                kategorien, belege);
    }

    private List<Counted> load(YearMonth monat) {
        Specification<Buchung> spec = (r, q, cb) -> cb.and(
                r.get("art").in(Art.AUSGABE, Art.KONTOAUSGABE),
                r.get("status").in(COUNTED),
                cb.greaterThanOrEqualTo(r.<LocalDate>get("datum"), monat.atDay(1)),
                cb.lessThanOrEqualTo(r.<LocalDate>get("datum"), monat.atEndOfMonth()));
        List<Buchung> rows = buchungen.findAll(spec, Sort.by("datum", "nummer"));
        Map<UUID, List<BuchungPosition>> byBooking = rows.isEmpty() ? Map.of()
                : positions.findByBuchungIdInOrderByPositionAsc(rows.stream().map(b -> b.id).toList()).stream()
                .collect(Collectors.groupingBy(p -> p.buchungId));
        List<Counted> result = new ArrayList<>();
        for (Buchung b : rows) {
            List<BuchungPosition> ps = byBooking.getOrDefault(b.id, List.of());
            // Altdaten ohne Positionen zählen mit ihrem Buchungsbetrag
            List<Part> parts = ps.isEmpty() ? List.of(new Part(b.kategorieId, b.betrag.negate()))
                    : ps.stream().map(p -> new Part(p.kategorieId, p.betrag)).toList();
            result.add(new Counted(b, parts, parts.stream().map(Part::betrag).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return result;
    }

    private static BigDecimal total(List<Counted> list) {
        return list.stream().map(Counted::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String summary(YearMonth monat, BigDecimal summe, int anzahl, BigDecimal vormonat, BigDecimal aenderung,
                                  BigDecimal prozent, Map<String, BigDecimal> perMain, CategoryTree tree) {
        if (anzahl == 0) {
            return "Keine Ausgaben in diesem Monat.";
        }
        String vor = GermanFormat.month(monat.minusMonths(1));
        StringBuilder s = new StringBuilder("Im ").append(GermanFormat.month(monat)).append(" wurden ")
                .append(GermanFormat.money(summe)).append(" für ").append(anzahl == 1 ? "1 Beleg" : anzahl + " Belege").append(" ausgegeben");
        if (vormonat.signum() == 0) {
            s.append("; im ").append(vor).append(" gab es keine Ausgaben");
        } else if (aenderung.signum() == 0) {
            s.append(", genauso viel wie im ").append(vor);
        } else {
            s.append(", ").append(GermanFormat.percent(prozent.abs())).append(aenderung.signum() > 0 ? " mehr" : " weniger")
                    .append(" als im ").append(vor).append(" (").append(GermanFormat.money(vormonat)).append(")");
        }
        s.append(". ");
        List<String> top = perMain.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(3)
                .map(e -> e.getKey() + " (" + GermanFormat.money(e.getValue()) + ", " + GermanFormat.percent(share(e.getValue(), summe)) + ")")
                .toList();
        s.append(top.size() == 1 ? "Der größte Posten war " : "Die größten Posten waren ").append(joinGerman(top)).append(".");
        return s.toString();
    }

    private static String joinGerman(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " und " + items.get(items.size() - 1);
    }
}
```

`CategoryTree tree` im `summary`-Parameter wird nicht gebraucht: den Parameter und das Argument beim Aufruf entfernen, falls der Compiler eine Warnung meldet (kein Verhaltensunterschied).

- [ ] **Step 9: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=MonthlyReportServiceTest+GermanFormatTest`
Expected: alle Tests grün. Falls `leapDayBelongsToFebruary` an einer Tenant- oder Datumsfrage scheitert, die Fehlermeldung lesen und den Test nicht abschwächen.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/de/grouppurse/reports src/test/java/de/grouppurse/reports
git commit -m "feat(report): Monatsbericht der Ausgaben (Zahlen, Anteile, Vormonat, Fließtext)"
```

---

### Task 2: PDF-Renderer (OpenPDF)

**Files:**
- Modify: `pom.xml` (Abhängigkeit)
- Create: `src/main/java/de/grouppurse/reports/MonthlyReportPdf.java`
- Test: `src/test/java/de/grouppurse/reports/MonthlyReportPdfTest.java`

**Interfaces:**
- Consumes: `ReportDtos.MonthlyReport` und Unter-Records, `GermanFormat` (Task 1).
- Produces: `MonthlyReportPdf` (`@Component`), `byte[] render(MonthlyReport report, String erstelltVon, LocalDate erstelltAm)`, `static String latin(String)` (package-private, ersetzt nicht darstellbare Zeichen durch „?“).

- [ ] **Step 1: Add the dependency**

In `pom.xml` nach der springdoc-Zeile einfügen:

```xml
        <dependency><groupId>com.github.librepdf</groupId><artifactId>openpdf</artifactId><version>3.0.5</version></dependency>
```

- [ ] **Step 2: Write the failing PDF test**

`src/test/java/de/grouppurse/reports/MonthlyReportPdfTest.java`:

```java
package de.grouppurse.reports;

import de.grouppurse.domain.Konto;
import de.grouppurse.reports.ReportDtos.MonthlyBeleg;
import de.grouppurse.reports.ReportDtos.MonthlyCategory;
import de.grouppurse.reports.ReportDtos.MonthlyPosition;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyReportPdfTest {
    MonthlyReportPdf pdf = new MonthlyReportPdf();

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static MonthlyBeleg beleg(int nr, String haendler, String betrag, boolean pruefen, MonthlyPosition... parts) {
        List<MonthlyPosition> positionen = parts.length > 0 ? List.of(parts) : List.of(new MonthlyPosition("Haushalt", bd(betrag)));
        return new MonthlyBeleg(LocalDate.of(2026, 10, 1 + (nr % 28)), nr, nr % 2 == 0 ? Konto.VEREINSKONTO : Konto.BARKASSE, haendler,
                "Petra Pflege", bd(betrag), pruefen, positionen);
    }

    static MonthlyReport report(List<MonthlyBeleg> belege, String zusammenfassung) {
        BigDecimal summe = belege.stream().map(MonthlyBeleg::betrag).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MonthlyCategory> kategorien = belege.isEmpty() ? List.of()
                : List.of(new MonthlyCategory("Haushalt", null, summe, bd("100.0")));
        return new MonthlyReport("2026-10", "WG Sonnenschein", summe, belege.size(), bd("7.00"), summe.subtract(bd("7.00")), bd("1328.6"),
                (int) belege.stream().filter(MonthlyBeleg::zuPruefen).count(), bd("20.00"), zusammenfassung, kategorien, belege);
    }

    String page(byte[] bytes, int n) throws IOException {
        return new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(n);
    }

    int pages(byte[] bytes) throws IOException {
        return new PdfReader(bytes).getNumberOfPages();
    }

    @Test
    void containsHeaderTablesAndFooter() throws IOException {
        var r = report(List.of(
                beleg(1, "REWE", "20.00", false),
                beleg(2, "Kaufland", "80.00", true,
                        new MonthlyPosition("Haushalt", bd("50.00")), new MonthlyPosition("Obst und Gemüse", bd("30.00")))),
                "Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.");
        byte[] bytes = pdf.render(r, "Karla Kassenverwalter", LocalDate.of(2026, 10, 11));
        assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
        String text = page(bytes, 1);
        assertThat(text).contains("WG Sonnenschein").contains("Oktober 2026").contains("Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.");
        assertThat(text).contains("Ausgaben nach Kategorie").contains("Alle Ausgaben");
        assertThat(text).contains("REWE").contains("Kaufland").contains("zu prüfen").contains("Obst und Gemüse");
        assertThat(text).contains("100,00 €").contains("Petra Pflege");
        assertThat(text).contains("Noch nicht geprüft").contains("20,00 €");
        assertThat(text).contains("Erstellt am 11.10.2026 von Karla Kassenverwalter").contains("Seite 1");
    }

    @Test
    void anEmptyMonthPrintsOnlyTheSentence() throws IOException {
        byte[] bytes = pdf.render(report(List.of(), "Keine Ausgaben in diesem Monat."), "Karla", LocalDate.of(2026, 10, 11));
        String text = page(bytes, 1);
        assertThat(text).contains("Keine Ausgaben in diesem Monat.");
        assertThat(text).doesNotContain("Ausgaben nach Kategorie").doesNotContain("Alle Ausgaben");
        assertThat(pages(bytes)).isEqualTo(1);
    }

    @Test
    void longListBreaksPagesAndRepeatsHeader() throws IOException {
        List<MonthlyBeleg> many = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            many.add(beleg(i, "Händler " + i, "1.50", false));
        }
        byte[] bytes = pdf.render(report(many, "Viele Belege."), "Karla", LocalDate.of(2026, 10, 11));
        int n = pages(bytes);
        assertThat(n).isGreaterThan(2);
        assertThat(page(bytes, 2)).contains("Datum").contains("Betrag");
        assertThat(page(bytes, n)).contains("Händler 120").contains("Seite " + n);
    }

    @Test
    void awkwardTextNeverBreaksRendering() throws IOException {
        String unbroken = "X".repeat(300);
        var r = report(List.of(
                beleg(1, "Café 😀 Ωmega\nZeile", "5.00", false),
                beleg(2, unbroken, "6.00", false),
                beleg(3, null, "7.00", false)), "Text.");
        byte[] bytes = pdf.render(r, "Zoë ☃ Bergmann", LocalDate.of(2026, 10, 11));
        assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
        assertThat(page(bytes, 1)).contains("Caf").contains("XXXXXXXXXX");
    }

    @Test
    void latinReplacesUnsupportedCharacters() {
        assertThat(MonthlyReportPdf.latin("Café € Ωmega 😀")).isEqualTo("Café € ?mega ?");
        assertThat(MonthlyReportPdf.latin("a\nb\tc")).isEqualTo("a b c");
        assertThat(MonthlyReportPdf.latin(null)).isEqualTo("–");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=MonthlyReportPdfTest`
Expected: compilation error, `MonthlyReportPdf` does not exist.

- [ ] **Step 4: Write `MonthlyReportPdf`**

`src/main/java/de/grouppurse/reports/MonthlyReportPdf.java`:

```java
package de.grouppurse.reports;

import de.grouppurse.domain.Konto;
import de.grouppurse.reports.ReportDtos.MonthlyBeleg;
import de.grouppurse.reports.ReportDtos.MonthlyCategory;
import de.grouppurse.reports.ReportDtos.MonthlyPosition;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.YearMonth;

/** Erzeugt den PDF-Monatsbericht (DIN A4 hoch) aus einem {@link MonthlyReport}. */
@Component
public class MonthlyReportPdf {
    private static final Color GREY = new Color(0x66, 0x66, 0x66);
    private static final Color HEAD_BG = new Color(0xE9, 0xEC, 0xEF);
    private static final Color LINE = new Color(0xCC, 0xCC, 0xCC);
    private static final Charset WIN_1252 = Charset.forName("windows-1252");

    public byte[] render(MonthlyReport r, String erstelltVon, LocalDate erstelltAm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 48, 56);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer(latin("Erstellt am " + GermanFormat.date(erstelltAm) + " von " + erstelltVon)));
        doc.open();

        doc.add(paragraph("Monatsbericht Ausgaben", font(18, Font.BOLD, Color.BLACK), 0, 2));
        doc.add(paragraph(r.gruppe() + " · " + GermanFormat.month(YearMonth.parse(r.monat())), font(11, Font.NORMAL, GREY), 0, 10));

        if (r.belegAnzahl() == 0) {
            doc.add(paragraph(r.zusammenfassung(), font(11, Font.NORMAL, Color.BLACK), 6, 6));
            doc.close();
            return out.toByteArray();
        }

        doc.add(keyFigures(r));
        doc.add(paragraph(r.zusammenfassung(), font(10, Font.NORMAL, Color.BLACK), 8, 6));
        doc.add(paragraph("Ausgaben nach Kategorie", font(13, Font.BOLD, Color.BLACK), 10, 4));
        doc.add(categoryTable(r));
        doc.add(paragraph("Alle Ausgaben", font(13, Font.BOLD, Color.BLACK), 14, 4));
        doc.add(belegTable(r));
        doc.close();
        return out.toByteArray();
    }

    private static PdfPTable keyFigures(MonthlyReport r) {
        PdfPTable t = new PdfPTable(new float[]{3f, 5f});
        t.setWidthPercentage(100);
        row(t, "Summe der Ausgaben", GermanFormat.money(r.summe()));
        row(t, "Anzahl Belege", String.valueOf(r.belegAnzahl()));
        row(t, "Vormonat (" + GermanFormat.month(YearMonth.parse(r.monat()).minusMonths(1)) + ")", GermanFormat.money(r.vormonatSumme()));
        String change = GermanFormat.signedMoney(r.aenderung());
        if (r.aenderungProzent() != null) {
            change += " (" + GermanFormat.signedPercent(r.aenderungProzent()) + ")";
        }
        row(t, "Veränderung", change);
        if (r.ungeprueftAnzahl() > 0) {
            row(t, "Noch nicht geprüft", (r.ungeprueftAnzahl() == 1 ? "1 Beleg" : r.ungeprueftAnzahl() + " Belege") + " (" + GermanFormat.money(r.ungeprueftSumme()) + ")");
        }
        return t;
    }

    private static void row(PdfPTable t, String label, String value) {
        t.addCell(cell(label, font(10, Font.NORMAL, GREY), Element.ALIGN_LEFT, null, Rectangle.NO_BORDER));
        t.addCell(cell(value, font(10, Font.BOLD, Color.BLACK), Element.ALIGN_LEFT, null, Rectangle.NO_BORDER));
    }

    private static PdfPTable categoryTable(MonthlyReport r) {
        PdfPTable t = new PdfPTable(new float[]{3f, 3f, 2f, 1.5f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        head(t, "Hauptkategorie", Element.ALIGN_LEFT);
        head(t, "Unterkategorie", Element.ALIGN_LEFT);
        head(t, "Betrag", Element.ALIGN_RIGHT);
        head(t, "Anteil", Element.ALIGN_RIGHT);
        Font f = font(9, Font.NORMAL, Color.BLACK);
        for (MonthlyCategory c : r.kategorien()) {
            t.addCell(cell(c.hauptkategorie(), f, Element.ALIGN_LEFT, null, Rectangle.BOTTOM));
            t.addCell(cell(c.unterkategorie() == null ? "" : c.unterkategorie(), f, Element.ALIGN_LEFT, null, Rectangle.BOTTOM));
            t.addCell(cell(GermanFormat.money(c.betrag()), f, Element.ALIGN_RIGHT, null, Rectangle.BOTTOM));
            t.addCell(cell(GermanFormat.percent(c.anteilProzent()), f, Element.ALIGN_RIGHT, null, Rectangle.BOTTOM));
        }
        Font b = font(9, Font.BOLD, Color.BLACK);
        t.addCell(cell("Summe", b, Element.ALIGN_LEFT, HEAD_BG, Rectangle.NO_BORDER));
        t.addCell(cell("", b, Element.ALIGN_LEFT, HEAD_BG, Rectangle.NO_BORDER));
        t.addCell(cell(GermanFormat.money(r.summe()), b, Element.ALIGN_RIGHT, HEAD_BG, Rectangle.NO_BORDER));
        t.addCell(cell(GermanFormat.percent(new BigDecimal("100")), b, Element.ALIGN_RIGHT, HEAD_BG, Rectangle.NO_BORDER));
        return t;
    }

    private static PdfPTable belegTable(MonthlyReport r) {
        PdfPTable t = new PdfPTable(new float[]{1.7f, 1f, 1.6f, 3f, 3f, 2.6f, 1.9f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        head(t, "Datum", Element.ALIGN_LEFT);
        head(t, "Nr.", Element.ALIGN_LEFT);
        head(t, "Konto", Element.ALIGN_LEFT);
        head(t, "Händler", Element.ALIGN_LEFT);
        head(t, "Kategorie", Element.ALIGN_LEFT);
        head(t, "Erfasst von", Element.ALIGN_LEFT);
        head(t, "Betrag", Element.ALIGN_RIGHT);
        Font f = font(8.5f, Font.NORMAL, Color.BLACK);
        Font small = font(7.5f, Font.ITALIC, GREY);
        for (MonthlyBeleg b : r.belege()) {
            boolean several = b.positionen().size() > 1;
            int border = several ? Rectangle.NO_BORDER : Rectangle.BOTTOM;
            t.addCell(cell(GermanFormat.date(b.datum()), f, Element.ALIGN_LEFT, null, border));
            t.addCell(cell(String.valueOf(b.nummer()), f, Element.ALIGN_LEFT, null, border));
            t.addCell(cell(b.konto() == Konto.BARKASSE ? "Barkasse" : "Vereinskonto", f, Element.ALIGN_LEFT, null, border));
            Phrase haendler = new Phrase(latin(b.haendler()), f);
            if (b.zuPruefen()) {
                haendler.add(new org.openpdf.text.Chunk("\nzu prüfen", small));
            }
            PdfPCell hc = new PdfPCell(haendler);
            style(hc, Element.ALIGN_LEFT, null, border);
            t.addCell(hc);
            t.addCell(cell(several ? "mehrere" : b.positionen().isEmpty() ? "–" : b.positionen().get(0).kategorie(), f, Element.ALIGN_LEFT, null, border));
            t.addCell(cell(b.erfasstVon(), f, Element.ALIGN_LEFT, null, border));
            t.addCell(cell(GermanFormat.money(b.betrag()), f, Element.ALIGN_RIGHT, null, border));
            if (several) {
                int i = 0;
                for (MonthlyPosition p : b.positionen()) {
                    int last = ++i == b.positionen().size() ? Rectangle.BOTTOM : Rectangle.NO_BORDER;
                    for (int c = 0; c < 4; c++) {
                        t.addCell(cell("", small, Element.ALIGN_LEFT, null, last));
                    }
                    t.addCell(cell("· " + p.kategorie(), small, Element.ALIGN_LEFT, null, last));
                    t.addCell(cell("", small, Element.ALIGN_LEFT, null, last));
                    t.addCell(cell(GermanFormat.money(p.betrag()), small, Element.ALIGN_RIGHT, null, last));
                }
            }
        }
        return t;
    }

    private static void head(PdfPTable t, String text, int align) {
        t.addCell(cell(text, font(9, Font.BOLD, Color.BLACK), align, HEAD_BG, Rectangle.NO_BORDER));
    }

    private static PdfPCell cell(String text, Font font, int align, Color background, int border) {
        PdfPCell c = new PdfPCell(new Phrase(latin(text), font));
        style(c, align, background, border);
        return c;
    }

    private static void style(PdfPCell c, int align, Color background, int border) {
        c.setHorizontalAlignment(align);
        c.setBorder(border);
        c.setBorderColor(LINE);
        c.setPadding(4);
        if (background != null) {
            c.setBackgroundColor(background);
        }
    }

    private static Paragraph paragraph(String text, Font font, float before, float after) {
        Paragraph p = new Paragraph(latin(text), font);
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    /** Ersetzt Zeichen, die die Standardschrift (Windows-1252) nicht darstellt, durch „?“ und Steuerzeichen durch Leerzeichen. */
    static String latin(String text) {
        if (text == null) {
            return "–";
        }
        CharsetEncoder encoder = WIN_1252.newEncoder();
        StringBuilder s = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7F) {
                s.append(' ');
            } else if (cp <= 0xFFFF && encoder.canEncode((char) cp)) {
                s.append((char) cp);
            } else {
                s.append('?');
            }
        });
        return s.toString();
    }

    private static final class Footer extends PdfPageEventHelper {
        private final String left;

        Footer(String left) {
            this.left = left;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Font f = font(8, Font.NORMAL, GREY);
            float y = document.bottom() - 20;
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT, new Phrase(left, f), document.left(), y, 0);
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, new Phrase("Seite " + writer.getPageNumber(), f), document.right(), y, 0);
        }
    }
}
```

Bemerkung: `latin("–")` für `null` liefert den Halbgeviertstrich (in Windows-1252 enthalten). Ein leerer Händlername (`""`) bleibt leer.

- [ ] **Step 5: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=MonthlyReportPdfTest`
Expected: 5 Tests grün. Wenn die Text-Extraktion einzelne Wörter anders trennt (z. B. Zeilenumbruch mitten im Satz), die Assertion auf kürzere, zusammenhängende Teile ändern (`contains("100,00 €")` statt ganzer Sätze); die Erzeugung selbst nicht ändern. Wenn `longListBreaksPagesAndRepeatsHeader` nur 2 Seiten ergibt, die Belegzahl im Test auf 200 erhöhen.

- [ ] **Step 6: Open the PDF once and look at it**

Ein Test schreibt zum Ansehen eine Datei; temporär in `containsHeaderTablesAndFooter` einfügen, ausführen, öffnen, wieder entfernen:

```java
java.nio.file.Files.write(java.nio.file.Path.of("target/monatsbericht-sicht.pdf"), bytes);
```

Run: `./mvnw -q test -Dtest=MonthlyReportPdfTest#containsHeaderTablesAndFooter` und `target/monatsbericht-sicht.pdf` öffnen. Prüfen: Titel, Kennzahlen, Tabellen ohne abgeschnittene Spalten, Aufteilung unter dem Beleg „Kaufland“, Fußzeile. Die Zeile danach wieder löschen.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/de/grouppurse/reports/MonthlyReportPdf.java src/test/java/de/grouppurse/reports/MonthlyReportPdfTest.java
git commit -m "feat(report): PDF-Monatsbericht mit OpenPDF"
```

---

### Task 3: Endpunkte, Rechte, Metrik

**Files:**
- Modify: `src/main/java/de/grouppurse/metrics/GkMetrics.java`
- Modify: `src/main/java/de/grouppurse/reports/ReportController.java`
- Test: `src/test/java/de/grouppurse/metrics/GkMetricsTest.java`
- Test: `src/test/java/de/grouppurse/reports/MonthlyReportControllerTest.java`

**Interfaces:**
- Consumes: `MonthlyReportService.report(YearMonth)`, `MonthlyReportPdf.render(...)` (Tasks 1 und 2), `CurrentUser.principal()` → `AppPrincipal.name()`.
- Produces: `GET /api/reports/monthly` (Operation-ID `monthlyReport`, Antwort `MonthlyReport`), `GET /api/reports/monthly.pdf` (im OpenAPI-Vertrag verborgen), `GkMetrics.reportPdf(String outcome)`.

- [ ] **Step 1: Write the failing metric test**

In `GkMetricsTest` eine Methode ergänzen:

```java
    @Test
    void pdfReportsAreCountedByOutcome() {
        m.reportPdf("ok");
        m.reportPdf("ok");
        m.reportPdf("error");
        assertThat(registry.get("gk.report.pdf").tag("outcome", "ok").counter().count()).isEqualTo(2);
        assertThat(registry.get("gk.report.pdf").tag("outcome", "error").counter().count()).isEqualTo(1);
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=GkMetricsTest`
Expected: compilation error, `reportPdf` is missing.

- [ ] **Step 3: Add the metric**

In `GkMetrics` vor `aiCall` einfügen:

```java
    /** ok oder error beim Erzeugen eines PDF-Berichts. */
    public void reportPdf(String outcome) {
        registry.counter("gk.report.pdf", "outcome", outcome).increment();
    }
```

Run: `./mvnw -q test -Dtest=GkMetricsTest` → grün.

- [ ] **Step 4: Write the failing controller test**

`src/test/java/de/grouppurse/reports/MonthlyReportControllerTest.java`:

```java
package de.grouppurse.reports;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonthlyReportControllerTest extends AbstractIntegrationTest {
    @Autowired MeterRegistry registry;

    @Test
    void managerGetsTheReportAsJson() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10").session(kv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monat").value("2026-10"))
                .andExpect(jsonPath("$.gruppe").value("Bericht"))
                .andExpect(jsonPath("$.summe").value(0))
                .andExpect(jsonPath("$.zusammenfassung").value("Keine Ausgaben in diesem Monat."))
                .andExpect(jsonPath("$.belege.length()").value(0));
    }

    @Test
    void managerGetsAPdfWithDownloadHeaders() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        double before = registry.counter("gk.report.pdf", "outcome", "ok").count();
        byte[] body = mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10").session(kv))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Gruppenkasse-2026-10.pdf\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
        assertThat(registry.counter("gk.report.pdf", "outcome", "ok").count()).isEqualTo(before + 1);
    }

    @Test
    void caregiverIsForbiddenForBoth() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10").session(pk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10").session(pk)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10")).andExpect(status().is4xxClientError());
    }

    @Test
    void invalidAndFutureMonthsAreBadRequests() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        for (String path : new String[]{"/api/reports/monthly", "/api/reports/monthly.pdf"}) {
            for (String bad : new String[]{"2026-13", "abc", "2026-1", "2026-10-01", "", YearMonth.now().plusMonths(1).toString()}) {
                mvc.perform(get(path).param("monat", bad).session(kv)).andExpect(status().isBadRequest());
            }
            mvc.perform(get(path).session(kv)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theCurrentMonthIsAllowed() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(get("/api/reports/monthly").param("monat", YearMonth.now().toString()).session(kv)).andExpect(status().isOk());
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=MonthlyReportControllerTest`
Expected: Tests schlagen mit 404 fehl (Endpunkte fehlen).

- [ ] **Step 6: Extend `ReportController`**

Imports ergänzen: `de.grouppurse.metrics.GkMetrics`, `de.grouppurse.reports.ReportDtos.MonthlyReport`, `de.grouppurse.security.CurrentUser`, `io.swagger.v3.oas.annotations.Hidden`, `org.springframework.http.HttpHeaders`, `org.springframework.http.HttpStatus`, `org.springframework.http.MediaType`, `org.springframework.http.ResponseEntity`, `org.springframework.web.server.ResponseStatusException`, `java.time.YearMonth`, `java.time.format.DateTimeParseException`.

Felder und Konstruktor ersetzen:

```java
    private final Access access;
    private final ReportService reports;
    private final MonthlyReportService monthly;
    private final MonthlyReportPdf pdfs;
    private final GkMetrics metrics;
    private final CurrentUser user;

    public ReportController(Access access, ReportService reports, MonthlyReportService monthly, MonthlyReportPdf pdfs,
                            GkMetrics metrics, CurrentUser user) {
        this.access = access;
        this.reports = reports;
        this.monthly = monthly;
        this.pdfs = pdfs;
        this.metrics = metrics;
        this.user = user;
    }
```

Methoden vor der letzten Klammer einfügen:

```java
    private static YearMonth month(String monat) {
        YearMonth m;
        try {
            m = YearMonth.parse(monat);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Monat, erwartet JJJJ-MM");
        }
        if (m.isAfter(YearMonth.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Der Monat liegt in der Zukunft");
        }
        return m;
    }

    @GetMapping("/reports/monthly")
    public MonthlyReport monthlyReport(@RequestParam String monat) {
        access.requireKassenverwalter();
        return monthly.report(month(monat));
    }

    /** Download-Link der Oberfläche, deshalb nicht im generierten Client. */
    @Hidden
    @GetMapping(value = "/reports/monthly.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> monthlyPdf(@RequestParam String monat) {
        access.requireKassenverwalter();
        YearMonth m = month(monat);
        try {
            byte[] pdf = pdfs.render(monthly.report(m), user.principal().orElseThrow().name(), LocalDate.now());
            metrics.reportPdf("ok");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Gruppenkasse-" + m + ".pdf\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(pdf);
        } catch (RuntimeException e) {
            metrics.reportPdf("error");
            throw e;
        }
    }
```

`@RequestParam String monat` ist Pflicht, ein fehlender Parameter ergibt 400. Ein leerer Wert (`""`) landet in `YearMonth.parse` und ergibt ebenfalls 400.

- [ ] **Step 7: Run it to verify it passes**

Run: `./mvnw -q test -Dtest=MonthlyReportControllerTest+GkMetricsTest`
Expected: grün. Wenn `anonymousIsRejected` ein Redirect (3xx) liefert, zeigt der Test, wie `SecurityConfig` anonyme API-Aufrufe beantwortet; dann `is4xxClientError()` durch den tatsächlichen, bestehenden Status ersetzen (wie in `AuthFlowTest`), nicht die Sicherheitskonfiguration ändern.

- [ ] **Step 8: Run the whole backend suite**

Run: `./mvnw -q verify`
Expected: alle Tests grün (vorher 147).

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat(report): Endpunkte für Monatsbericht (JSON und PDF), Rechte und Metrik"
```

---

### Task 4: Frontend (Monatsübersicht auf der Seite Auswertungen)

**Files:**
- Modify: `frontend/openapi.json`, `frontend/src/api/generated/**`, `frontend/src/api/model/**` (generiert)
- Modify: `frontend/src/lib/format.ts`
- Test: `frontend/src/lib/format.test.ts`
- Create: `frontend/src/components/MonthlyOverview.tsx`
- Test: `frontend/src/components/MonthlyOverview.test.tsx`
- Modify: `frontend/src/pages/manager/ReportsPage.tsx`

**Interfaces:**
- Consumes: `useMonthlyReport({ monat })` aus `frontend/src/api/generated/report-controller/report-controller` (Antwort `{ status: 200, data: MonthlyReport }`), Typen `MonthlyReport`, `MonthlyCategory`, `MonthlyBeleg`, `MonthlyPosition` aus `frontend/src/api/model`.
- Produces: `formatMonthLong(ym)`, `recentMonths(count)` in `format.ts`; `MonthlyOverview` (keine Props).

- [ ] **Step 1: Regenerate the API client**

```bash
./mvnw -q test -Dtest=SpaAndOpenApiTest -Dopenapi.out=frontend/openapi.json
cd frontend && npm run api:generate && cd ..
git diff --stat frontend
```

Expected: `openapi.json` und `report-controller.ts` ändern sich, neue Dateien `MonthlyReport.ts`, `MonthlyCategory.ts`, `MonthlyBeleg.ts`, `MonthlyPosition.ts` entstehen, `useMonthlyReport` existiert, und `monthly.pdf` taucht **nicht** auf (`@Hidden`). Prüfen: `grep -n "useMonthlyReport" frontend/src/api/generated/report-controller/report-controller.ts`.

- [ ] **Step 2: Write the failing format tests**

In `frontend/src/lib/format.test.ts` ergänzen (Imports `formatMonthLong`, `recentMonths` hinzufügen; `vi`, `afterEach` aus vitest importieren, falls noch nicht da):

```ts
describe('formatMonthLong', () => {
  it('writes the month in full German', () => {
    expect(formatMonthLong('2026-03')).toBe('März 2026');
    expect(formatMonthLong('2026-10')).toBe('Oktober 2026');
  });
});

describe('recentMonths', () => {
  afterEach(() => vi.useRealTimers());

  it('lists the current month first and goes back across year ends', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 1, 15));
    expect(recentMonths(4)).toEqual(['2026-02', '2026-01', '2025-12', '2025-11']);
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/lib/format.test.ts`
Expected: FAIL, `formatMonthLong` is not exported.

- [ ] **Step 4: Implement the helpers**

In `frontend/src/lib/format.ts` nach `formatMonth` einfügen:

```ts
const MONTHS_LONG = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

/** 2026-03 -> März 2026 */
export function formatMonthLong(ym: string): string {
  const [y, m] = ym.split('-');
  return `${MONTHS_LONG[Number(m) - 1]} ${y}`;
}

/** Die letzten count Monate, der laufende zuerst. */
export function recentMonths(count: number): string[] {
  const now = currentMonth();
  return Array.from({ length: count }, (_, i) => shiftMonth(now, -i));
}
```

Run: `npx vitest run src/lib/format.test.ts` → grün.

- [ ] **Step 5: Write the failing component test**

`frontend/src/components/MonthlyOverview.test.tsx`:

```tsx
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MonthlyOverview } from './MonthlyOverview';
import { renderWithProviders } from '../test/utils';
import * as api from '../api/generated/report-controller/report-controller';
import type { MonthlyReport } from '../api/model';

vi.mock('../api/generated/report-controller/report-controller', () => ({ useMonthlyReport: vi.fn() }));

const full: MonthlyReport = {
  monat: '2026-10',
  gruppe: 'WG Sonne',
  summe: 100,
  belegAnzahl: 2,
  vormonatSumme: 7,
  aenderung: 93,
  aenderungProzent: 1328.6,
  ungeprueftAnzahl: 1,
  ungeprueftSumme: 20,
  zusammenfassung: 'Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.',
  kategorien: [
    { hauptkategorie: 'Haushalt', unterkategorie: undefined, betrag: 60, anteilProzent: 60 },
    { hauptkategorie: 'Lebensmittel', unterkategorie: 'Obst und Gemüse', betrag: 40, anteilProzent: 40 },
  ],
  belege: [
    { datum: '2026-10-01', nummer: 1, konto: 'BARKASSE', haendler: 'REWE', erfasstVon: 'Petra', betrag: 20, zuPruefen: true, positionen: [{ kategorie: 'Obst und Gemüse', betrag: 20 }] },
    { datum: '2026-10-15', nummer: 2, konto: 'VEREINSKONTO', haendler: undefined, erfasstVon: 'Karla', betrag: 80, zuPruefen: false,
      positionen: [{ kategorie: 'Haushalt', betrag: 60 }, { kategorie: 'Obst und Gemüse', betrag: 20 }] },
  ],
};

const empty: MonthlyReport = { ...full, summe: 0, belegAnzahl: 0, aenderung: -7, aenderungProzent: -100, ungeprueftAnzahl: 0, ungeprueftSumme: 0,
  zusammenfassung: 'Keine Ausgaben in diesem Monat.', kategorien: [], belege: [] };

function mockReport(data: MonthlyReport) {
  vi.mocked(api.useMonthlyReport).mockReturnValue({ data: { status: 200, data }, isLoading: false, isError: false } as never);
}

describe('MonthlyOverview', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 11));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('shows summary, category table and receipts for the current month', () => {
    mockReport(full);
    renderWithProviders(<MonthlyOverview />);
    expect(vi.mocked(api.useMonthlyReport)).toHaveBeenCalledWith({ monat: '2026-10' });
    expect(screen.getByRole('heading', { name: 'Monatsübersicht' })).toBeInTheDocument();
    expect(screen.getByText('Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Hauptkategorie' })).toBeInTheDocument();
    expect(screen.getByText('Lebensmittel')).toBeInTheDocument();
    expect(screen.getByText('REWE')).toBeInTheDocument();
    expect(screen.getByText('zu prüfen')).toBeInTheDocument();
    expect(screen.getByText('Noch nicht geprüft: 1 Beleg', { exact: false })).toBeInTheDocument();
  });

  it('says so for an empty month and shows no tables', () => {
    mockReport(empty);
    renderWithProviders(<MonthlyOverview />);
    expect(screen.getByText('Keine Ausgaben in diesem Monat.')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Hauptkategorie' })).not.toBeInTheDocument();
  });

  it('links the PDF for the selected month and moves between months', async () => {
    mockReport(full);
    renderWithProviders(<MonthlyOverview />);
    const link = screen.getByRole('link', { name: 'PDF herunterladen' });
    expect(link).toHaveAttribute('href', '/api/reports/monthly.pdf?monat=2026-10');
    expect(screen.getByRole('button', { name: 'Nächster Monat' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Vormonat' }));
    expect(vi.mocked(api.useMonthlyReport)).toHaveBeenLastCalledWith({ monat: '2026-09' });
    expect(screen.getByRole('link', { name: 'PDF herunterladen' })).toHaveAttribute('href', '/api/reports/monthly.pdf?monat=2026-09');
    expect(screen.getByRole('button', { name: 'Nächster Monat' })).toBeEnabled();
  });
});
```

- [ ] **Step 6: Run it to verify it fails**

Run: `npx vitest run src/components/MonthlyOverview.test.tsx`
Expected: FAIL, `MonthlyOverview` does not exist.

- [ ] **Step 7: Write the component**

`frontend/src/components/MonthlyOverview.tsx`:

```tsx
import { useMemo, useState } from 'react';
import { Badge, Button, Card, Group, Select, Stack, Table, Text, Title } from '@mantine/core';
import { useMonthlyReport } from '../api/generated/report-controller/report-controller';
import { LoadError, Loading } from './Loading';
import { currentMonth, formatDate, formatEuro, formatMonthLong, recentMonths, shiftMonth } from '../lib/format';

const MONTH_COUNT = 60;

const formatPercent = (value: number | undefined) => `${(value ?? 0).toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} %`;
const kontoLabel = (konto: string | undefined) => (konto === 'VEREINSKONTO' ? 'Vereinskonto' : 'Barkasse');

export function MonthlyOverview() {
  const [monat, setMonat] = useState(currentMonth());
  const options = useMemo(() => recentMonths(MONTH_COUNT).map((m) => ({ value: m, label: formatMonthLong(m) })), []);
  const oldest = options[options.length - 1].value;
  const report = useMonthlyReport({ monat });
  const data = report.data?.status === 200 ? report.data.data : null;

  return (
    <Card withBorder>
      <Group justify="space-between" align="flex-end" mb="sm" wrap="wrap">
        <Title order={4}>Monatsübersicht</Title>
        <Group align="flex-end" gap="xs">
          <Button variant="default" aria-label="Vormonat" disabled={monat <= oldest} onClick={() => setMonat(shiftMonth(monat, -1))}>‹</Button>
          <Select aria-label="Monat" data={options} value={monat} onChange={(v) => v && setMonat(v)} allowDeselect={false} w={190} />
          <Button variant="default" aria-label="Nächster Monat" disabled={monat >= currentMonth()} onClick={() => setMonat(shiftMonth(monat, 1))}>›</Button>
          <Button component="a" href={`/api/reports/monthly.pdf?monat=${monat}`} download>PDF herunterladen</Button>
        </Group>
      </Group>

      {report.isLoading ? (
        <Loading />
      ) : report.isError ? (
        <LoadError error={report.error} />
      ) : data ? (
        <Stack gap="md">
          <Text>{data.zusammenfassung}</Text>
          {data.ungeprueftAnzahl ? (
            <Text size="sm" c="dimmed">
              Noch nicht geprüft: {data.ungeprueftAnzahl === 1 ? '1 Beleg' : `${data.ungeprueftAnzahl} Belege`} ({formatEuro(data.ungeprueftSumme)})
            </Text>
          ) : null}
          {data.belegAnzahl > 0 && (
            <>
              <Title order={5}>Ausgaben nach Kategorie</Title>
              <Table.ScrollContainer minWidth={420}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Hauptkategorie</Table.Th>
                      <Table.Th>Unterkategorie</Table.Th>
                      <Table.Th ta="right">Betrag</Table.Th>
                      <Table.Th ta="right">Anteil</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {data.kategorien?.map((k) => (
                      <Table.Tr key={`${k.hauptkategorie}/${k.unterkategorie ?? ''}`}>
                        <Table.Td>{k.hauptkategorie}</Table.Td>
                        <Table.Td>{k.unterkategorie ?? ''}</Table.Td>
                        <Table.Td ta="right">{formatEuro(k.betrag)}</Table.Td>
                        <Table.Td ta="right">{formatPercent(k.anteilProzent)}</Table.Td>
                      </Table.Tr>
                    ))}
                    <Table.Tr fw={600}>
                      <Table.Td>Summe</Table.Td>
                      <Table.Td />
                      <Table.Td ta="right">{formatEuro(data.summe)}</Table.Td>
                      <Table.Td ta="right">{formatPercent(100)}</Table.Td>
                    </Table.Tr>
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>

              <Title order={5}>Alle Ausgaben</Title>
              <Table.ScrollContainer minWidth={640}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Datum</Table.Th>
                      <Table.Th>Nr.</Table.Th>
                      <Table.Th>Konto</Table.Th>
                      <Table.Th>Händler</Table.Th>
                      <Table.Th>Kategorie</Table.Th>
                      <Table.Th>Erfasst von</Table.Th>
                      <Table.Th ta="right">Betrag</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {data.belege?.map((b) => {
                      const several = (b.positionen?.length ?? 0) > 1;
                      return [
                        <Table.Tr key={b.nummer}>
                          <Table.Td>{formatDate(b.datum)}</Table.Td>
                          <Table.Td>{b.nummer}</Table.Td>
                          <Table.Td>{kontoLabel(b.konto)}</Table.Td>
                          <Table.Td>
                            {b.haendler ?? '–'} {b.zuPruefen && <Badge size="xs" variant="light" color="yellow">zu prüfen</Badge>}
                          </Table.Td>
                          <Table.Td>{several ? 'mehrere' : b.positionen?.[0]?.kategorie ?? '–'}</Table.Td>
                          <Table.Td>{b.erfasstVon}</Table.Td>
                          <Table.Td ta="right">{formatEuro(b.betrag)}</Table.Td>
                        </Table.Tr>,
                        ...(several
                          ? (b.positionen ?? []).map((p, i) => (
                              <Table.Tr key={`${b.nummer}-${i}`} c="dimmed" fz="xs">
                                <Table.Td colSpan={4} />
                                <Table.Td colSpan={2}>· {p.kategorie}</Table.Td>
                                <Table.Td ta="right">{formatEuro(p.betrag)}</Table.Td>
                              </Table.Tr>
                            ))
                          : []),
                      ];
                    })}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </>
          )}
        </Stack>
      ) : null}
    </Card>
  );
}
```

Hinweis: Tabellenzeilen als Array von Arrays rendern funktioniert in React; die inneren `key`-Werte sind eindeutig (`nummer`, `nummer-i`). Falls `Loading`/`LoadError` einen anderen Pfad oder andere Props haben als in `ReportsPage.tsx` (`import { LoadError, Loading } from '../../components/Loading'`, `<LoadError error={...} />`), nach diesem Vorbild anpassen. Falls `Table.Tr` kein `fw`/`c`/`fz` als Style-Props nimmt, `style={{ fontWeight: 600 }}` verwenden.

- [ ] **Step 8: Run it to verify it passes**

Run: `npx vitest run src/components/MonthlyOverview.test.tsx`
Expected: 3 Tests grün.

- [ ] **Step 9: Put it on the page**

In `frontend/src/pages/manager/ReportsPage.tsx` den Import `import { MonthlyOverview } from '../../components/MonthlyOverview';` ergänzen und direkt nach `<Title order={2}>Auswertungen</Title>` einfügen:

```tsx
      <MonthlyOverview />
```

- [ ] **Step 10: Typecheck, build and run all frontend tests**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: keine Typfehler, alle Tests grün (vorher 37).

- [ ] **Step 11: Look at it in the running app**

Dev-Umgebung läuft (Backend 8080, Vite 5173). Als Kassenverwalter anmelden, *Auswertungen* öffnen, Monat wechseln, „PDF herunterladen“ klicken, PDF öffnen. Prüfen: Karte oben, Tabellen scrollen auf schmaler Breite (Fenster verkleinern), Download funktioniert. Falls das Backend vor Task 3 gestartet wurde, neu starten.

- [ ] **Step 12: Commit**

```bash
git add frontend
git commit -m "feat(report): Monatsübersicht mit PDF-Download auf der Seite Auswertungen"
```

---

### Task 5: E2E-Test, Spec-Abgleich, Gesamtlauf

**Files:**
- Create: `e2e/tests/monthly-report.spec.ts`
- Modify: `README.md` (eine Zeile), falls dort Funktionen aufgezählt werden

**Interfaces:**
- Consumes: Fixtures `test`, `expect`, `openAs` aus `../support/fixtures`; `isoMonth` aus `../support/api`; `eur`, `pick` aus `../support/ui`; `world.manager.api`, `world.member.api` (`manualExpense`, `get`).
- Produces: nichts für andere Tasks.

- [ ] **Step 1: Write the e2e test**

`e2e/tests/monthly-report.spec.ts`:

```ts
import { readFileSync } from 'node:fs';
import { test, expect, openAs } from '../support/fixtures';
import { isoMonth } from '../support/api';
import { eur, pick } from '../support/ui';

const monthName = (offset: number) => {
  const [y, m] = isoMonth(offset).split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleString('de-DE', { month: 'long', year: 'numeric' });
};

test.describe('Monatsübersicht und PDF-Bericht', () => {
  test('the manager sees the month as text and tables and downloads the PDF', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await world.member.api.manualExpense({ positions: [['Obst und Gemüse', 20], ['Haushalt', 10]], haendler: 'REWE' });
    await world.manager.api.manualExpense({ positions: [['Getränke', 5]], haendler: 'Kiosk' });

    await openAs(page, world.manager, '/auswertungen');
    const card = page.locator('.mantine-Card-root', { has: page.getByRole('heading', { name: 'Monatsübersicht' }) });
    await expect(card.getByText(/wurden 35,00\s€ für 2 Belege ausgegeben/)).toBeVisible();
    await expect(card.getByRole('columnheader', { name: 'Hauptkategorie' })).toBeVisible();
    await expect(card.getByRole('cell', { name: 'REWE' })).toBeVisible();
    await expect(card.getByRole('cell', { name: 'Kiosk' })).toBeVisible();
    await expect(card.getByRole('cell', { name: eur(35) })).toBeVisible();

    const [download] = await Promise.all([page.waitForEvent('download'), card.getByRole('link', { name: 'PDF herunterladen' }).click()]);
    expect(download.suggestedFilename()).toBe(`Gruppenkasse-${isoMonth(0)}.pdf`);
    const bytes = readFileSync(await download.path());
    expect(bytes.subarray(0, 4).toString()).toBe('%PDF');
    expect(bytes.length).toBeGreaterThan(1500);
  });

  test('an empty month says so and the month can be switched', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await world.member.api.manualExpense({ positions: [['Haushalt', 12]], haendler: 'Baumarkt' });
    await openAs(page, world.manager, '/auswertungen');
    const card = page.locator('.mantine-Card-root', { has: page.getByRole('heading', { name: 'Monatsübersicht' }) });
    await expect(card.getByRole('cell', { name: 'Baumarkt' })).toBeVisible();

    await card.getByRole('button', { name: 'Vormonat' }).click();
    await expect(card.getByText('Keine Ausgaben in diesem Monat.')).toBeVisible();
    await expect(card.getByRole('columnheader', { name: 'Hauptkategorie' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Nächster Monat' })).toBeEnabled();

    await pick(page, 'Monat', monthName(0));
    await expect(card.getByRole('cell', { name: 'Baumarkt' })).toBeVisible();
    await expect(card.getByRole('button', { name: 'Nächster Monat' })).toBeDisabled();
  });

  test('team members cannot read or download the report', async ({ world }) => {
    const monat = isoMonth(0);
    expect((await world.member.api.get(`/api/reports/monthly?monat=${monat}`)).status()).toBe(403);
    expect((await world.member.api.get(`/api/reports/monthly.pdf?monat=${monat}`)).status()).toBe(403);
    expect((await world.manager.api.get(`/api/reports/monthly.pdf?monat=${isoMonth(1)}`)).status()).toBe(400);
  });
});
```

Hinweis: Die `pick`-Hilfe öffnet ein Select per Label (`e2e/support/ui.ts:22`). Das Select trägt `aria-label="Monat"`; falls `pick` `getByLabel` verwendet, funktioniert das auch mit `aria-label`. Wenn nicht, `pick` ansehen und den Test an dessen Mechanik anpassen (z. B. `card.getByRole('textbox', { name: 'Monat' }).click()` und `page.getByRole('option', { name: ... }).click()`).

- [ ] **Step 2: Build and run the e2e suite for this file**

Run (der E2E-Lauf nutzt das gebaute JAR, deshalb zuerst bauen):

```bash
cd e2e && node scripts/build.mjs && npx playwright test tests/monthly-report.spec.ts tests/reports.spec.ts
```

Expected: alle Tests dieser beiden Dateien grün, besonders die unveränderten Tests in `reports.spec.ts` (die neue Karte darf `recharts-wrapper`-Zahl, Legenden und Filter nicht stören). Wenn dort ein Locator wegen der neuen Karte mehrdeutig wird (z. B. Text „Vereinskonto“ oder ein Label „Kategorie“), den Locator im alten Test auf die jeweilige Karte einschränken, die Komponente nicht umbauen.

- [ ] **Step 3: Run the complete verification**

```bash
./mvnw -q verify
cd frontend && npx tsc -b && npx vitest run && cd ..
cd e2e && npx playwright test && cd ..
```

Expected: Backend grün, Frontend grün, alle E2E-Tests grün (vorher 103, jetzt 106). Ergebnisse (Anzahlen) notieren und im Abschlussbericht nennen.

- [ ] **Step 4: Check the metric in the running stack**

Backend neu starten, PDF einmal herunterladen, dann:

```bash
curl -s localhost:8081/actuator/prometheus | grep gk_report_pdf
```

Expected: `gk_report_pdf_total{...,outcome="ok"} 1.0`.

- [ ] **Step 5: Commit**

```bash
git add e2e README.md
git commit -m "test(e2e): Monatsübersicht und PDF-Bericht"
```

(`README.md` nur hinzufügen, wenn dort eine Funktionsliste um „Monatsbericht als PDF“ ergänzt wurde.)

- [ ] **Step 6: Report**

Dem Nutzer berichten: was gebaut ist, die Testzahlen (Backend, Frontend, E2E), dass das PDF einmal angesehen wurde, und dass Push und Deployment noch ausstehen (wie üblich erst auf Wunsch pushen; auf der VM danach `./deploy.sh latest` nach grüner Pipeline).

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
            haushalt = categories.visible().stream().filter(c -> c.name().equals("Haushalt und Drogerie")).findFirst().orElseThrow().id();
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
        assertThat(first.hauptkategorie()).isEqualTo("Haushalt und Drogerie");
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
                        + "Die größten Posten waren Haushalt und Drogerie (60,00 €, 60,0 %) und Lebensmittel (40,00 €, 40,0 %).");
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
        assertThat(beleg.positionen()).extracting("kategorie").containsExactly("Haushalt und Drogerie", "Obst und Gemüse");
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
        assertThat(r.kategorien().get(0).hauptkategorie()).isEqualTo("Haushalt und Drogerie");
        assertThat(r.belege().get(0).positionen()).hasSize(1);
    }

    @Test
    void zeroTotalDoesNotDivideByZero() {
        // Positionen mit Betrag 0 verbietet die Datenbank; möglich ist eine Altdaten-Buchung ohne Positionen über 0,00
        t.as(kv, g, () -> {
            Buchung b = new Buchung();
            b.nummer = ++nr;
            b.konto = Konto.BARKASSE;
            b.art = Art.AUSGABE;
            b.status = BuchungStatus.GEBUCHT;
            b.datum = LocalDate.of(2026, 10, 2);
            b.kategorieId = haushalt;
            b.erfasstVon = pk.id;
            b.betrag = bd("0.00");
            buchungen.save(b);
        });
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

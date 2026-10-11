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

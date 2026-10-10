package de.grouppurse.export;

import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Beitrag;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Familie;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Jahresabschluss für die Kassenprüfung: Kontostände, Einnahmen, Ausgaben nach Kategorie und das vollständige Kassenbuch. */
@Service
public class AnnualReportPdfService {
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8);

    private final BuchungRepository buchungen;
    private final BeitragRepository beitraege;
    private final FamilieRepository familien;
    private final WohngruppeRepository gruppen;
    private final CategoryService categories;

    public AnnualReportPdfService(BuchungRepository buchungen, BeitragRepository beitraege, FamilieRepository familien,
                                  WohngruppeRepository gruppen, CategoryService categories) {
        this.buchungen = buchungen;
        this.beitraege = beitraege;
        this.familien = familien;
        this.gruppen = gruppen;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public void write(int year, OutputStream out) throws IOException {
        Wohngruppe g = gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow();
        List<Buchung> all = buchungen.findAll();
        List<Beitrag> income = beitraege.findByStorniertFalse();
        Map<UUID, String> categoryNames = categories.namesById();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);

        Document doc = new Document(PageSize.A4, 36, 36, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph("Jahresabschluss " + year, TITLE));
            doc.add(new Paragraph("Wohngruppe: " + g.name + "   |   erstellt am " + ExportFormat.date(LocalDate.now()), BODY));
            doc.add(new Paragraph(" "));

            accountSection(doc, all, income, start, end);
            incomeSection(doc, income, start, end);
            expenseSection(doc, all, categoryNames, year);
            ledgerSection(doc, all, categoryNames, year);
            doc.close();
        } catch (DocumentException e) {
            throw new IOException("PDF konnte nicht erzeugt werden", e);
        }
    }

    private void accountSection(Document doc, List<Buchung> all, List<Beitrag> income, LocalDate start, LocalDate end) throws DocumentException {
        doc.add(new Paragraph("1. Kontostände", H2));
        PdfPTable t = table(new float[]{3, 2.5f, 2.5f, 2.5f, 2.5f});
        header(t, "Konto", "Anfangsbestand " + ExportFormat.date(start), "Zugänge", "Abgänge", "Endbestand " + ExportFormat.date(end));
        for (Konto konto : Konto.values()) {
            BigDecimal opening = BigDecimal.ZERO;
            BigDecimal in = BigDecimal.ZERO;
            BigDecimal outgoing = BigDecimal.ZERO;
            for (Buchung b : all) {
                if (b.konto != konto) {
                    continue;
                }
                if (b.datum.isBefore(start)) {
                    opening = opening.add(b.betrag);
                } else if (!b.datum.isAfter(end)) {
                    if (b.betrag.signum() >= 0) {
                        in = in.add(b.betrag);
                    } else {
                        outgoing = outgoing.add(b.betrag.negate());
                    }
                }
            }
            if (konto == Konto.VEREINSKONTO) {
                for (Beitrag b : income) {
                    if (b.eingangsdatum.isBefore(start)) {
                        opening = opening.add(b.betrag);
                    } else if (!b.eingangsdatum.isAfter(end)) {
                        in = in.add(b.betrag);
                    }
                }
            }
            row(t, konto == Konto.BARKASSE ? "Barkasse" : "Vereinskonto", ExportFormat.euro(opening), ExportFormat.euro(in),
                    ExportFormat.euro(outgoing), ExportFormat.euro(opening.add(in).subtract(outgoing)));
        }
        doc.add(t);
        doc.add(new Paragraph(" "));
    }

    private void incomeSection(Document doc, List<Beitrag> income, LocalDate start, LocalDate end) throws DocumentException {
        doc.add(new Paragraph("2. Einnahmen (Elternbeiträge)", H2));
        Map<UUID, BigDecimal> perFamily = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Beitrag b : income) {
            if (!b.eingangsdatum.isBefore(start) && !b.eingangsdatum.isAfter(end)) {
                perFamily.merge(b.familieId, b.betrag, BigDecimal::add);
                total = total.add(b.betrag);
            }
        }
        Map<UUID, Familie> families = new HashMap<>();
        familien.findAll().forEach(f -> families.put(f.id, f));
        PdfPTable t = table(new float[]{5, 2, 3});
        header(t, "Familie", "Bewohner", "Summe");
        perFamily.entrySet().stream()
                .sorted(Comparator.comparing(e -> families.get(e.getKey()).name, String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> {
                    Familie f = families.get(e.getKey());
                    row(t, f.name, f.bewohnerKuerzel == null ? "" : f.bewohnerKuerzel, ExportFormat.euro(e.getValue()));
                });
        row(t, "Summe Einnahmen", "", ExportFormat.euro(total));
        doc.add(t);
        doc.add(new Paragraph(" "));
    }

    private void expenseSection(Document doc, List<Buchung> all, Map<UUID, String> categoryNames, int year) throws DocumentException {
        doc.add(new Paragraph("3. Ausgaben nach Kategorie", H2));
        Map<String, BigDecimal> perCategory = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        BigDecimal total = BigDecimal.ZERO;
        for (Buchung b : all) {
            if (b.datum.getYear() == year && (b.art == Art.AUSGABE || b.art == Art.KONTOAUSGABE) && b.status != BuchungStatus.STORNIERT) {
                String name = b.kategorieId == null ? "Ohne Kategorie" : categoryNames.getOrDefault(b.kategorieId, "Ohne Kategorie");
                perCategory.merge(name, b.betrag.negate(), BigDecimal::add);
                total = total.add(b.betrag.negate());
            }
        }
        PdfPTable t = table(new float[]{6, 3});
        header(t, "Kategorie", "Summe");
        perCategory.forEach((k, v) -> row(t, k, ExportFormat.euro(v)));
        row(t, "Summe Ausgaben", ExportFormat.euro(total));
        doc.add(t);
        doc.add(new Paragraph(" "));
    }

    private void ledgerSection(Document doc, List<Buchung> all, Map<UUID, String> categoryNames, int year) throws DocumentException {
        doc.add(new Paragraph("4. Kassenbuch " + year, H2));
        PdfPTable t = table(new float[]{1, 2, 2.2f, 2.4f, 2.2f, 5, 2.2f});
        header(t, "Nr.", "Datum", "Konto", "Art", "Betrag", "Kategorie / Zweck", "Status");
        all.stream().filter(b -> b.datum.getYear() == year).sorted(Comparator.comparingLong((Buchung b) -> b.nummer)).forEach(b -> {
            String what = (b.kategorieId == null ? "" : categoryNames.getOrDefault(b.kategorieId, "") + " ")
                    + (b.zweck == null ? "" : b.zweck);
            t.addCell(cell(String.valueOf(b.nummer), SMALL, Element.ALIGN_RIGHT));
            t.addCell(cell(ExportFormat.date(b.datum), SMALL, Element.ALIGN_LEFT));
            t.addCell(cell(b.konto == Konto.BARKASSE ? "Barkasse" : "Vereinskonto", SMALL, Element.ALIGN_LEFT));
            t.addCell(cell(b.art.name(), SMALL, Element.ALIGN_LEFT));
            t.addCell(cell(ExportFormat.euro(b.betrag), SMALL, Element.ALIGN_RIGHT));
            t.addCell(cell(what.trim(), SMALL, Element.ALIGN_LEFT));
            t.addCell(cell(b.status.name(), SMALL, Element.ALIGN_LEFT));
        });
        doc.add(t);
    }

    private static PdfPTable table(float[] widths) throws DocumentException {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setHeaderRows(1);
        return t;
    }

    private static void header(PdfPTable t, String... labels) {
        for (String l : labels) {
            PdfPCell c = new PdfPCell(new Phrase(l, BOLD));
            c.setBackgroundColor(new java.awt.Color(230, 230, 230));
            c.setPadding(3);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, String... values) {
        for (int i = 0; i < values.length; i++) {
            t.addCell(cell(values[i], BODY, i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
        }
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(2.5f);
        return c;
    }
}

package de.grouppurse.reports;

import de.grouppurse.domain.Konto;
import de.grouppurse.reports.ReportDtos.MonthlyBeleg;
import de.grouppurse.reports.ReportDtos.MonthlyCategory;
import de.grouppurse.reports.ReportDtos.MonthlyPosition;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import org.openpdf.text.Chunk;
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
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
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
        PdfPTable t = new PdfPTable(new float[]{1.7f, 0.9f, 2.2f, 2.8f, 2.8f, 2.5f, 1.9f});
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
                haendler.add(new Chunk("\nzu prüfen", small));
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

package de.grouppurse.export;

import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.Access;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/** Exporte für die Kassenprüfung. Geschrieben wird direkt in die Antwort, damit Sitzung und Mandant erhalten bleiben. */
@RestController
@RequestMapping("/api/export")
public class ExportController {
    private final Access access;
    private final WohngruppeRepository gruppen;
    private final AnnualReportPdfService pdf;
    private final CsvExportService csv;
    private final ReceiptZipService zip;

    public ExportController(Access access, WohngruppeRepository gruppen, AnnualReportPdfService pdf, CsvExportService csv, ReceiptZipService zip) {
        this.access = access;
        this.gruppen = gruppen;
        this.pdf = pdf;
        this.csv = csv;
        this.zip = zip;
    }

    private String prepare(int jahr, HttpServletResponse res, String contentType, String extension) {
        access.requireKassenverwalter();
        if (jahr < 2000 || jahr > 2100) {
            throw new IllegalArgumentException("Jahr ungültig");
        }
        UUID id = access.requireGroup();
        Wohngruppe g = gruppen.findById(id).orElseThrow();
        String slug = g.name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        String name = "Gruppenkasse_" + (slug.isEmpty() ? "Wohngruppe" : slug) + "_" + jahr + "." + extension;
        res.setContentType(contentType);
        res.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        res.setHeader("Cache-Control", "no-store");
        return name;
    }

    @GetMapping("/annual-report.pdf")
    public void annualReport(@RequestParam int jahr, HttpServletResponse res) throws IOException {
        prepare(jahr, res, "application/pdf", "pdf");
        pdf.write(jahr, res.getOutputStream());
    }

    @GetMapping("/bookings.csv")
    public void bookings(@RequestParam int jahr, HttpServletResponse res) throws IOException {
        prepare(jahr, res, "text/csv;charset=UTF-8", "csv");
        csv.write(jahr, res.getOutputStream());
    }

    @GetMapping("/receipts.zip")
    public void receipts(@RequestParam int jahr, HttpServletResponse res) throws IOException {
        prepare(jahr, res, "application/zip", "zip");
        zip.write(jahr, res.getOutputStream());
    }
}

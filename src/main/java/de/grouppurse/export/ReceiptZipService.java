package de.grouppurse.export;

import de.grouppurse.domain.Buchung;
import de.grouppurse.expense.ReceiptStorage;
import de.grouppurse.repo.BuchungRepository;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ReceiptZipService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BuchungRepository buchungen;
    private final ReceiptStorage storage;

    public ReceiptZipService(BuchungRepository buchungen, ReceiptStorage storage) {
        this.buchungen = buchungen;
        this.storage = storage;
    }

    /** Alle Belegfotos des Jahres, benannt nach Datum und Buchungsnummer. Es wird direkt in den Ausgabestrom geschrieben. */
    @Transactional(readOnly = true)
    public void write(int year, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        var rows = buchungen.findAll().stream()
                .filter(b -> b.datum.getYear() == year && !b.belegFotos.isEmpty())
                .sorted(Comparator.comparingLong((Buchung b) -> b.nummer)).toList();
        for (Buchung b : rows) {
            for (int i = 0; i < b.belegFotos.size(); i++) {
                String stored = b.belegFotos.get(i);
                Resource res = storage.load(stored);
                if (res == null) {
                    continue;
                }
                String ext = stored.substring(stored.lastIndexOf('.') + 1);
                zip.putNextEntry(new ZipEntry("%s_%05d_%d.%s".formatted(ISO.format(b.datum), b.nummer, i + 1, ext)));
                try (InputStream in = res.getInputStream()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        zip.finish();
    }
}

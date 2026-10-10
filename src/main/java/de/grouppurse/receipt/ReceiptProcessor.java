package de.grouppurse.receipt;

import de.grouppurse.expense.ExpenseService;
import de.grouppurse.expense.ReceiptStorage;
import de.grouppurse.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hintergrundjob: liest Belege im Status VERARBEITUNG mit der KI und schreibt das Ergebnis an den Beleg. Der Auftrag steht in der
 * Datenbank und übersteht damit Neustarts. Während des (langen) KI-Aufrufs wird keine Datenbanktransaktion offen gehalten.
 */
@Component
public class ReceiptProcessor {
    private static final Logger log = LoggerFactory.getLogger(ReceiptProcessor.class);
    private static final int BATCH = 3;

    private final JdbcTemplate jdbc;
    private final ReceiptRecognitionService recognition;
    private final ReceiptStorage storage;
    private final ExpenseService expenses;
    private final ReceiptAiProperties props;

    public ReceiptProcessor(JdbcTemplate jdbc, ReceiptRecognitionService recognition, ReceiptStorage storage,
                            ExpenseService expenses, ReceiptAiProperties props) {
        this.jdbc = jdbc;
        this.recognition = recognition;
        this.storage = storage;
        this.expenses = expenses;
        this.props = props;
    }

    record Claim(UUID id, UUID wohngruppeId) {
    }

    @Scheduled(fixedDelayString = "${app.receipt.poll-delay-ms:15000}", initialDelayString = "${app.receipt.poll-delay-ms:20000}")
    public void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Belegverarbeitung: {}", e.toString());
        }
    }

    /** Bearbeitet fällige Belege und gibt zurück, wie viele es waren. Auch für Tests direkt aufrufbar. */
    public int runOnce() {
        if (!props.enabled()) {
            return 0;
        }
        List<Claim> claims = claim();
        claims.forEach(this::process);
        return claims.size();
    }

    /** Reserviert fällige Belege (über alle Wohngruppen hinweg, deshalb direkt per SQL ohne Mandantenfilter). */
    private List<Claim> claim() {
        return jdbc.query("""
                update buchung set verarbeitung_gestartet_am = now(), verarbeitung_versuche = verarbeitung_versuche + 1
                where id in (
                    select id from buchung
                    where status = 'VERARBEITUNG'
                      and (verarbeitung_naechster_versuch is null or verarbeitung_naechster_versuch <= now())
                      and (verarbeitung_gestartet_am is null or verarbeitung_gestartet_am < now() - interval '10 minutes')
                    order by erfasst_am
                    limit ?
                    for update skip locked)
                returning id, wohngruppe_id
                """, (rs, i) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("wohngruppe_id", UUID.class)), BATCH);
    }

    private void process(Claim c) {
        TenantContext.runAs(c.wohngruppeId(), () -> {
            try {
                List<String> names = expenses.photoNamesIfPending(c.id());
                if (names.isEmpty()) {
                    return; // inzwischen vom Kassenverwalter erfasst oder verworfen
                }
                List<ReceiptImage> images = names.stream().map(this::load).flatMap(Optional::stream).toList();
                if (images.isEmpty()) {
                    expenses.recordRecognitionFailure(c.id(), "Das Belegfoto wurde nicht gefunden.");
                    return;
                }
                Optional<ReceiptSuggestion> result = recognition.recognize(images);
                if (result.isPresent()) {
                    expenses.applyRecognition(c.id(), result.get());
                } else {
                    expenses.recordRecognitionFailure(c.id(), "Die KI konnte den Beleg nicht eindeutig lesen.");
                }
            } catch (RuntimeException e) {
                log.warn("Beleg {} konnte nicht verarbeitet werden: {}", c.id(), e.toString());
                try {
                    expenses.recordRecognitionFailure(c.id(), "Fehler bei der Verarbeitung: " + e.getMessage());
                } catch (RuntimeException inner) {
                    log.warn("Fehler konnte nicht vermerkt werden: {}", inner.toString());
                }
            }
        });
    }

    private Optional<ReceiptImage> load(String name) {
        var res = storage.load(name);
        if (res == null) {
            return Optional.empty();
        }
        try (InputStream in = res.getInputStream()) {
            return Optional.of(new ReceiptImage(in.readAllBytes(), ReceiptStorage.contentType(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

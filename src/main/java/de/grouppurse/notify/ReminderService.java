package de.grouppurse.notify;

import de.grouppurse.contributions.ContributionDtos.Cell;
import de.grouppurse.contributions.ContributionDtos.CellStatus;
import de.grouppurse.contributions.ContributionDtos.GridRow;
import de.grouppurse.contributions.GridService;
import de.grouppurse.domain.Familie;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.mail.MailService;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Erinnerungs-Mail an Familien mit überfälligen Beiträgen; wird vom Kassenverwalter ausgelöst. */
@Service
public class ReminderService {
    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN);

    private final GridService grid;
    private final FamilieRepository familien;
    private final WohngruppeRepository gruppen;
    private final MailService mail;

    public ReminderService(GridService grid, FamilieRepository familien, WohngruppeRepository gruppen, MailService mail) {
        this.grid = grid;
        this.familien = familien;
        this.gruppen = gruppen;
        this.mail = mail;
    }

    /** Anzahl erfolgreich versendeter Mails. Familien ohne Kontaktadresse werden übersprungen. */
    @Transactional(readOnly = true)
    public int sendOverdueReminders(LocalDate heute) {
        YearMonth now = YearMonth.from(heute);
        Wohngruppe g = gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow();
        Map<UUID, Familie> byId = familien.findAll().stream().collect(Collectors.toMap(f -> f.id, f -> f));
        NumberFormat eur = NumberFormat.getCurrencyInstance(Locale.GERMANY);
        int sent = 0;
        for (GridRow row : grid.grid(now.minusYears(10), now, heute).zeilen()) {
            Familie f = byId.get(row.familieId());
            if (f == null || !f.aktiv || f.kontaktEmail == null || f.kontaktEmail.isBlank()) {
                continue;
            }
            List<Cell> overdue = row.cells().stream().filter(c -> c.status() == CellStatus.UEBERFAELLIG).toList();
            if (overdue.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder("Liebe Familie ").append(f.name).append(",\n\n")
                    .append("für die Gruppenkasse der Wohngruppe \"").append(g.name)
                    .append("\" ist der Elternbeitrag für folgende Monate noch nicht vollständig eingegangen:\n\n");
            for (Cell c : overdue) {
                sb.append("- ").append(YearMonth.parse(c.monat()).format(MONTH)).append(": offen ")
                        .append(eur.format(c.soll().subtract(c.bezahlt()))).append("\n");
            }
            sb.append("\nSollte die Überweisung bereits unterwegs sein, betrachte diese Nachricht bitte als gegenstandslos.\n");
            try {
                mail.send(f.kontaktEmail, "Erinnerung: Elternbeitrag " + g.name, sb.toString());
                sent++;
            } catch (RuntimeException e) {
                log.warn("Erinnerung an {} fehlgeschlagen: {}", f.kontaktEmail, e.toString());
            }
        }
        return sent;
    }
}

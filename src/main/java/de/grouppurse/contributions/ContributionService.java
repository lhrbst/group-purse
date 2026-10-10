package de.grouppurse.contributions;

import de.grouppurse.audit.AuditService;
import de.grouppurse.contributions.ContributionDtos.ContributionDto;
import de.grouppurse.contributions.ContributionDtos.ContributionRequest;
import de.grouppurse.domain.Beitrag;
import de.grouppurse.domain.Familie;
import de.grouppurse.ledger.LedgerService;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ContributionService {
    private final BeitragRepository beitraege;
    private final FamilieRepository familien;
    private final AuditService audit;
    private final CurrentUser user;

    public ContributionService(BeitragRepository beitraege, FamilieRepository familien, AuditService audit, CurrentUser user) {
        this.beitraege = beitraege;
        this.familien = familien;
        this.audit = audit;
        this.user = user;
    }

    @Transactional
    public Beitrag book(ContributionRequest r) {
        Familie f = familien.findById(r.familieId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Familie nicht gefunden"));
        var amount = LedgerService.money(r.betrag());
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Betrag muss größer als 0 sein");
        }
        if (r.monate() == null || r.monate().isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Monat muss angegeben werden");
        }
        Beitrag b = new Beitrag();
        b.familieId = f.id;
        b.betrag = amount;
        b.eingangsdatum = r.eingangsdatum() != null ? r.eingangsdatum() : LocalDate.now();
        b.monate = r.monate().stream().map(ContributionService::normalizeMonth).sorted().toList();
        b.notiz = r.notiz() == null || r.notiz().isBlank() ? null : r.notiz().trim();
        b.gebuchtVon = user.personId();
        b = beitraege.save(b);
        audit.log("Beitrag gebucht", "Beitrag", b.id, null, b);
        return b;
    }

    @Transactional
    public void cancel(UUID id, String begruendung) {
        if (begruendung == null || begruendung.isBlank()) {
            throw new IllegalArgumentException("Begründung ist Pflicht");
        }
        Beitrag b = beitraege.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Beitrag nicht gefunden"));
        if (b.storniert) {
            throw new IllegalStateException("Dieser Beitrag wurde bereits storniert");
        }
        b.storniert = true;
        b.stornoBegruendung = begruendung.trim();
        audit.log("Beitrag storniert", "Beitrag", b.id, false, true);
    }

    @Transactional(readOnly = true)
    public List<ContributionDto> list(UUID familieId) {
        List<Beitrag> list = familieId == null ? beitraege.findAllByOrderByEingangsdatumDesc()
                : beitraege.findByFamilieIdOrderByEingangsdatumDesc(familieId);
        Map<UUID, String> names = familien.findAll().stream().collect(Collectors.toMap(f -> f.id, f -> f.name));
        return list.stream().map(b -> new ContributionDto(b.id, b.familieId, names.get(b.familieId), b.betrag, b.eingangsdatum,
                b.monate, b.notiz, b.storniert, b.stornoBegruendung)).toList();
    }

    static String normalizeMonth(String m) {
        try {
            return YearMonth.parse(m.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Ungültiger Monat: " + m + " (erwartet JJJJ-MM)");
        }
    }
}

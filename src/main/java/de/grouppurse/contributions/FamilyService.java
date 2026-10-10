package de.grouppurse.contributions;

import de.grouppurse.audit.AuditService;
import de.grouppurse.contributions.ContributionDtos.FamilyDto;
import de.grouppurse.contributions.ContributionDtos.FamilyRequest;
import de.grouppurse.contributions.ContributionDtos.SollbetragDto;
import de.grouppurse.domain.Familie;
import de.grouppurse.domain.Sollbetrag;
import de.grouppurse.ledger.LedgerService;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.repo.SollbetragRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class FamilyService {
    private final FamilieRepository familien;
    private final SollbetragRepository sollbetraege;
    private final AuditService audit;

    public FamilyService(FamilieRepository familien, SollbetragRepository sollbetraege, AuditService audit) {
        this.familien = familien;
        this.sollbetraege = sollbetraege;
        this.audit = audit;
    }

    /** Sollbetrag, der für den Monat gilt: der jüngste Eintrag, dessen Gültigkeitsmonat nicht nach dem Monat liegt. */
    public static BigDecimal sollFor(List<Sollbetrag> history, YearMonth month) {
        BigDecimal result = BigDecimal.ZERO;
        LocalDate best = null;
        for (Sollbetrag s : history) {
            if (!YearMonth.from(s.gueltigAb).isAfter(month) && (best == null || s.gueltigAb.isAfter(best))) {
                best = s.gueltigAb;
                result = s.betrag;
            }
        }
        return result;
    }

    @Transactional
    public FamilyDto create(FamilyRequest r) {
        Familie f = new Familie();
        apply(f, r);
        f = familien.save(f);
        if (r.sollbetrag() != null) {
            setSollbetrag(f.id, r.sollbetrag(), r.gueltigAb() != null ? r.gueltigAb() : LocalDate.now().withDayOfMonth(1));
        }
        audit.log("Familie angelegt", "Familie", f.id, null, f);
        return toDto(f);
    }

    @Transactional
    public FamilyDto update(UUID id, FamilyRequest r) {
        Familie f = find(id);
        Familie before = copy(f);
        apply(f, r);
        audit.log("Familie geändert", "Familie", f.id, before, f);
        return toDto(f);
    }

    @Transactional
    public void setSollbetrag(UUID familieId, BigDecimal betrag, LocalDate gueltigAb) {
        find(familieId);
        BigDecimal v = LedgerService.money(betrag);
        if (v.signum() < 0) {
            throw new IllegalArgumentException("Sollbetrag darf nicht negativ sein");
        }
        Sollbetrag s = sollbetraege.findByFamilieIdAndGueltigAb(familieId, gueltigAb).orElseGet(() -> {
            Sollbetrag n = new Sollbetrag();
            n.familieId = familieId;
            n.gueltigAb = gueltigAb;
            return n;
        });
        BigDecimal old = s.betrag;
        s.betrag = v;
        sollbetraege.save(s);
        audit.log("Sollbetrag gesetzt", "Familie", familieId, old, v + " ab " + gueltigAb);
    }

    @Transactional(readOnly = true)
    public List<FamilyDto> list() {
        return familien.findAll().stream()
                .sorted(Comparator.comparing((Familie f) -> f.name, String.CASE_INSENSITIVE_ORDER))
                .map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<SollbetragDto> history(UUID familieId) {
        find(familieId);
        return sollbetraege.findByFamilieIdOrderByGueltigAbAsc(familieId).stream()
                .map(s -> new SollbetragDto(s.betrag, s.gueltigAb)).toList();
    }

    @Transactional(readOnly = true)
    public Familie find(UUID id) {
        return familien.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Familie nicht gefunden"));
    }

    private void apply(Familie f, FamilyRequest r) {
        f.name = r.name().trim();
        f.bewohnerKuerzel = blank(r.bewohnerKuerzel());
        f.kontaktEmail = blank(r.kontaktEmail());
        int day = r.faelligkeitstag() != null ? r.faelligkeitstag() : f.faelligkeitstag;
        if (day < 1 || day > 28) {
            throw new IllegalArgumentException("Fälligkeitstag muss zwischen 1 und 28 liegen");
        }
        f.faelligkeitstag = day;
        if (r.aktiv() != null) {
            f.aktiv = r.aktiv();
        }
    }

    private FamilyDto toDto(Familie f) {
        BigDecimal current = sollFor(sollbetraege.findByFamilieIdOrderByGueltigAbAsc(f.id), YearMonth.now());
        return new FamilyDto(f.id, f.name, f.bewohnerKuerzel, f.kontaktEmail, f.faelligkeitstag, f.aktiv, current);
    }

    private static Familie copy(Familie f) {
        Familie c = new Familie();
        c.id = f.id;
        c.name = f.name;
        c.bewohnerKuerzel = f.bewohnerKuerzel;
        c.kontaktEmail = f.kontaktEmail;
        c.faelligkeitstag = f.faelligkeitstag;
        c.aktiv = f.aktiv;
        return c;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

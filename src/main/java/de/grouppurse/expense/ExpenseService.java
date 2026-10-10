package de.grouppurse.expense;

import de.grouppurse.audit.AuditService;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.expense.ExpenseDtos.ExpenseInput;
import de.grouppurse.ledger.BarkasseChanged;
import de.grouppurse.ledger.BuchungDto;
import de.grouppurse.ledger.BuchungMapper;
import de.grouppurse.ledger.LedgerService;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.security.CurrentUser;
import de.grouppurse.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ExpenseService {
    static final int MAX_PHOTOS = 10;

    private final BuchungRepository buchungen;
    private final LedgerService ledger;
    private final CategoryService categories;
    private final ReceiptStorage storage;
    private final AuditService audit;
    private final CurrentUser user;
    private final BuchungMapper mapper;
    private final ApplicationEventPublisher events;

    public ExpenseService(BuchungRepository buchungen, LedgerService ledger, CategoryService categories, ReceiptStorage storage,
                          AuditService audit, CurrentUser user, BuchungMapper mapper, ApplicationEventPublisher events) {
        this.buchungen = buchungen;
        this.ledger = ledger;
        this.categories = categories;
        this.storage = storage;
        this.audit = audit;
        this.user = user;
        this.mapper = mapper;
        this.events = events;
    }

    /** Neue Ausgabe aus der Barkasse. Zieht den Betrag sofort vom Kassenstand ab. */
    @Transactional
    public Buchung submit(ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = new Buchung();
        b.konto = Konto.BARKASSE;
        b.art = Art.AUSGABE;
        b.status = BuchungStatus.EINGEREICHT;
        apply(b, in, photos, true);
        return ledger.save(b);
    }

    /** Ausgabe vom Vereinskonto (Kassenverwalter), sofort gebucht. */
    @Transactional
    public Buchung kontoausgabe(ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = new Buchung();
        b.konto = Konto.VEREINSKONTO;
        b.art = Art.KONTOAUSGABE;
        b.status = BuchungStatus.GEBUCHT;
        apply(b, in, photos, true);
        return ledger.save(b);
    }

    /** Pflegekraft korrigiert eine abgelehnte Ausgabe und reicht sie erneut ein. */
    @Transactional
    public Buchung resubmit(UUID id, ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = find(id);
        if (b.art != Art.AUSGABE || !b.erfasstVon.equals(user.personId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur die erfassende Person kann ihre Ausgabe korrigieren");
        }
        if (b.status != BuchungStatus.ABGELEHNT) {
            throw new IllegalStateException("Nur abgelehnte Ausgaben können korrigiert werden");
        }
        BuchungDto before = mapper.toDto(b);
        apply(b, in, photos, false);
        b.status = BuchungStatus.EINGEREICHT;
        b.ablehnungsKommentar = null;
        audit.log("Ausgabe erneut eingereicht", "Buchung", b.id, before, mapper.toDto(b));
        events.publishEvent(new BarkasseChanged(TenantContext.get().orElse(null)));
        return b;
    }

    @Transactional
    public Buchung approve(UUID id) {
        Buchung b = find(id);
        requireSubmitted(b);
        b.status = BuchungStatus.GEPRUEFT;
        b.geprueftVon = user.personId();
        b.geprueftAm = Instant.now();
        audit.log("Ausgabe geprüft", "Buchung", b.id, BuchungStatus.EINGEREICHT, BuchungStatus.GEPRUEFT);
        return b;
    }

    /** Lehnt ab: Der Betrag bleibt im Kassenstand abgezogen, bis die Ausgabe korrigiert oder storniert wird. */
    @Transactional
    public Buchung reject(UUID id, String kommentar) {
        if (kommentar == null || kommentar.isBlank()) {
            throw new IllegalArgumentException("Kommentar ist Pflicht");
        }
        Buchung b = find(id);
        requireSubmitted(b);
        b.status = BuchungStatus.ABGELEHNT;
        b.ablehnungsKommentar = kommentar.trim();
        audit.log("Ausgabe abgelehnt", "Buchung", b.id, BuchungStatus.EINGEREICHT, kommentar.trim());
        return b;
    }

    @Transactional(readOnly = true)
    public List<BuchungDto> mine(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return mapper.toDtos(buchungen.findByErfasstVonAndArtAndErfasstAmAfterOrderByNummerDesc(user.personId(), Art.AUSGABE, since));
    }

    @Transactional(readOnly = true)
    public List<BuchungDto> pending() {
        return mapper.toDtos(buchungen.findByStatusOrderByNummerAsc(BuchungStatus.EINGEREICHT));
    }

    @Transactional(readOnly = true)
    public List<BuchungDto> search(BuchungStatus status, UUID kategorie, LocalDate von, LocalDate bis, UUID person, String bewohner) {
        Specification<Buchung> spec = (r, q, cb) -> r.get("art").in(Art.AUSGABE, Art.KONTOAUSGABE);
        if (status != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        if (kategorie != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("kategorieId"), kategorie));
        }
        if (von != null) {
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.<LocalDate>get("datum"), von));
        }
        if (bis != null) {
            spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.<LocalDate>get("datum"), bis));
        }
        if (person != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("erfasstVon"), person));
        }
        if (bewohner != null && !bewohner.isBlank()) {
            String b = bewohner.trim().toLowerCase();
            spec = spec.and((r, q, cb) -> cb.equal(cb.lower(r.get("bewohnerKuerzel")), b));
        }
        return mapper.toDtos(buchungen.findAll(spec, PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "nummer"))).getContent());
    }

    @Transactional(readOnly = true)
    public BuchungDto get(UUID id, boolean manager) {
        Buchung b = find(id);
        if (!manager && !b.erfasstVon.equals(user.personId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff");
        }
        return mapper.toDto(b);
    }

    @Transactional(readOnly = true)
    public Buchung find(UUID id) {
        return buchungen.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Buchung nicht gefunden"));
    }

    private void requireSubmitted(Buchung b) {
        if (b.art != Art.AUSGABE || b.status != BuchungStatus.EINGEREICHT) {
            throw new IllegalStateException("Nur eingereichte Ausgaben können geprüft werden");
        }
    }

    /** Prüft die Eingaben und überträgt sie auf die Buchung. Neue Fotos ersetzen vorhandene, ohne neue bleiben die alten. */
    private void apply(Buchung b, ExpenseInput in, List<MultipartFile> photos, boolean creating) {
        BigDecimal amount = LedgerService.money(in.betrag());
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Betrag muss größer als 0 sein");
        }
        if (in.datum().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Das Datum darf nicht in der Zukunft liegen");
        }
        if (!categories.exists(in.kategorieId())) {
            throw new IllegalArgumentException("Kategorie ungültig");
        }
        List<MultipartFile> files = photos == null ? List.of() : photos.stream().filter(f -> !f.isEmpty()).toList();
        if (files.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("Höchstens " + MAX_PHOTOS + " Fotos je Beleg");
        }
        List<String> names = new ArrayList<>(creating ? List.of() : b.belegFotos);
        if (!files.isEmpty()) {
            names = files.stream().map(storage::store).toList();
        }
        String reason = in.begruendungOhneBeleg() == null || in.begruendungOhneBeleg().isBlank() ? null : in.begruendungOhneBeleg().trim();
        if (names.isEmpty() && reason == null) {
            throw new IllegalArgumentException("Ohne Belegfoto ist eine Begründung Pflicht");
        }
        b.betrag = amount.negate();
        b.datum = in.datum();
        b.kategorieId = in.kategorieId();
        b.haendler = blankToNull(in.haendler());
        b.zweck = blankToNull(in.zweck());
        b.bewohnerKuerzel = blankToNull(in.bewohnerKuerzel());
        b.belegFotos = new ArrayList<>(names);
        b.begruendungOhneBeleg = names.isEmpty() ? reason : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

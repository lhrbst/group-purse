package de.grouppurse.expense;

import de.grouppurse.audit.AuditService;
import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungPosition;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.expense.ExpenseDtos.ExpenseInput;
import de.grouppurse.expense.ExpenseDtos.PositionInput;
import de.grouppurse.ledger.BuchungDto;
import de.grouppurse.ledger.BuchungMapper;
import de.grouppurse.ledger.LedgerService;
import de.grouppurse.repo.BuchungPositionRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.security.CurrentUser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ExpenseService {
    static final int MAX_PHOTOS = 10;
    static final int MAX_POSITIONS = 30;

    private final BuchungRepository buchungen;
    private final BuchungPositionRepository positionen;
    private final LedgerService ledger;
    private final CategoryService categories;
    private final ReceiptStorage storage;
    private final AuditService audit;
    private final CurrentUser user;
    private final BuchungMapper mapper;

    public ExpenseService(BuchungRepository buchungen, BuchungPositionRepository positionen, LedgerService ledger,
                          CategoryService categories, ReceiptStorage storage, AuditService audit, CurrentUser user, BuchungMapper mapper) {
        this.buchungen = buchungen;
        this.positionen = positionen;
        this.ledger = ledger;
        this.categories = categories;
        this.storage = storage;
        this.audit = audit;
        this.user = user;
        this.mapper = mapper;
    }

    /** Neue Ausgabe aus der Barkasse. Zieht den Betrag sofort vom Kassenstand ab. */
    @Transactional
    public Buchung submit(ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = new Buchung();
        b.konto = Konto.BARKASSE;
        b.art = Art.AUSGABE;
        b.status = BuchungStatus.EINGEREICHT;
        List<PositionInput> rows = apply(b, in, photos, true);
        Buchung saved = ledger.save(b);
        replacePositions(saved.id, rows);
        return saved;
    }

    /** Ausgabe vom Vereinskonto (Kassenverwalter), sofort gebucht. */
    @Transactional
    public Buchung kontoausgabe(ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = new Buchung();
        b.konto = Konto.VEREINSKONTO;
        b.art = Art.KONTOAUSGABE;
        b.status = BuchungStatus.GEBUCHT;
        List<PositionInput> rows = apply(b, in, photos, true);
        Buchung saved = ledger.save(b);
        replacePositions(saved.id, rows);
        return saved;
    }

    /** Das Teammitglied korrigiert eine abgelehnte Ausgabe und reicht sie erneut ein. */
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
        List<PositionInput> rows = apply(b, in, photos, false);
        replacePositions(b.id, rows);
        b.status = BuchungStatus.EINGEREICHT;
        b.ablehnungsKommentar = null;
        audit.log("Ausgabe erneut eingereicht", "Buchung", b.id, before, mapper.toDto(b));
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
        List<BuchungDto> result = mapper.toDtos(buchungen.findAll(spec, PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "nummer"))).getContent());
        if (kategorie == null) {
            return result;
        }
        var tree = categories.tree();
        return result.stream()
                .filter(d -> d.positionen() != null && d.positionen().stream().anyMatch(p -> tree.isSameOrChildOf(p.kategorieId(), kategorie)))
                .toList();
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

    private void replacePositions(UUID buchungId, List<PositionInput> rows) {
        positionen.deleteByBuchungId(buchungId);
        positionen.flush();
        int i = 0;
        for (PositionInput p : rows) {
            BuchungPosition bp = new BuchungPosition();
            bp.buchungId = buchungId;
            bp.kategorieId = p.kategorieId();
            bp.betrag = p.betrag();
            bp.position = i++;
            positionen.save(bp);
        }
        audit.log("Positionen gesetzt", "Buchung", buchungId, null, rows);
    }

    /**
     * Prüft die Eingaben und überträgt sie auf die Buchung. Der Betrag der Ausgabe ist die Summe der Positionen;
     * gleiche Kategorien werden zusammengefasst. Neue Fotos ersetzen vorhandene, ohne neue bleiben die alten.
     * Gibt die bereinigten Positionen zurück, die danach gespeichert werden.
     */
    private List<PositionInput> apply(Buchung b, ExpenseInput in, List<MultipartFile> photos, boolean creating) {
        if (in.datum().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Das Datum darf nicht in der Zukunft liegen");
        }
        if (in.positionen() == null || in.positionen().isEmpty()) {
            throw new IllegalArgumentException("Mindestens eine Position mit Kategorie und Betrag ist nötig");
        }
        if (in.positionen().size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("Höchstens " + MAX_POSITIONS + " Positionen je Beleg");
        }
        Set<UUID> allowed = categories.leaves().stream().map(CategoryDto::id).collect(Collectors.toSet());
        Map<UUID, BigDecimal> merged = new LinkedHashMap<>();
        for (PositionInput p : in.positionen()) {
            BigDecimal amount = LedgerService.money(p.betrag());
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Jede Position braucht einen Betrag größer 0");
            }
            if (!allowed.contains(p.kategorieId())) {
                throw new IllegalArgumentException("Kategorie ungültig. Bei Kategorien mit Unterkategorien bitte eine Unterkategorie wählen.");
            }
            merged.merge(p.kategorieId(), amount, BigDecimal::add);
        }
        BigDecimal total = merged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

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
        b.betrag = total.negate();
        b.datum = in.datum();
        // stellvertretende Kategorie der Buchung: die mit dem größten Anteil
        b.kategorieId = merged.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        b.haendler = blankToNull(in.haendler());
        b.zweck = blankToNull(in.zweck());
        b.bewohnerKuerzel = blankToNull(in.bewohnerKuerzel());
        b.belegFotos = new ArrayList<>(names);
        b.begruendungOhneBeleg = names.isEmpty() ? reason : null;
        List<PositionInput> rows = new ArrayList<>();
        merged.forEach((k, v) -> rows.add(new PositionInput(k, v)));
        return rows;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

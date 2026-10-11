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
import de.grouppurse.metrics.GkMetrics;
import de.grouppurse.receipt.ReceiptAiProperties;
import de.grouppurse.receipt.ReceiptSuggestion;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
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
    /** Nach so vielen erfolglosen KI-Versuchen wird der Beleg zur manuellen Erfassung freigegeben. */
    public static final int MAX_ATTEMPTS = 3;
    /** Solange der Beleg noch nicht geprüft ist, darf der Kassenverwalter ihn bearbeiten. */
    private static final Set<BuchungStatus> EDITABLE = EnumSet.of(BuchungStatus.VERARBEITUNG, BuchungStatus.MANUELL, BuchungStatus.EINGEREICHT);

    private final BuchungRepository buchungen;
    private final BuchungPositionRepository positionen;
    private final LedgerService ledger;
    private final CategoryService categories;
    private final ReceiptStorage storage;
    private final AuditService audit;
    private final CurrentUser user;
    private final BuchungMapper mapper;
    private final ReceiptAiProperties ai;
    private final GkMetrics metrics;

    public ExpenseService(BuchungRepository buchungen, BuchungPositionRepository positionen, LedgerService ledger,
                          CategoryService categories, ReceiptStorage storage, AuditService audit, CurrentUser user,
                          BuchungMapper mapper, ReceiptAiProperties ai, GkMetrics metrics) {
        this.buchungen = buchungen;
        this.positionen = positionen;
        this.ledger = ledger;
        this.categories = categories;
        this.storage = storage;
        this.audit = audit;
        this.user = user;
        this.mapper = mapper;
        this.ai = ai;
        this.metrics = metrics;
    }

    /** Setzt den Status und zählt den Übergang (NEU bei der Erstanlage). */
    private void setStatus(Buchung b, BuchungStatus to) {
        metrics.expenseTransition(b.status == null ? "NEU" : b.status.name(), to.name());
        b.status = to;
    }

    /** Zeit von der Aufnahme bis zum Ergebnis der Belegerkennung (eingereicht oder zur manuellen Erfassung). */
    private void recordEndToEnd(Buchung b, BuchungStatus outcome) {
        if (b.erfasstAm != null) {
            metrics.receiptEndToEnd(outcome.name(), Duration.between(b.erfasstAm, Instant.now()));
        }
    }

    private BuchungStatus statusAfterUpload() {
        return ai.enabled() ? BuchungStatus.VERARBEITUNG : BuchungStatus.MANUELL;
    }

    private static List<MultipartFile> nonEmpty(List<MultipartFile> photos) {
        return photos == null ? List.of() : photos.stream().filter(f -> !f.isEmpty()).toList();
    }

    // ---------- Erfassen

    /**
     * Teammitglied macht nur Fotos. Der Beleg wird mit Betrag 0 gespeichert (der Kassenstand ändert sich erst, wenn die Daten
     * vorliegen) und im Hintergrund von der KI gelesen; ist die KI aus, geht er direkt zur manuellen Erfassung.
     */
    @Transactional
    public Buchung capture(List<MultipartFile> photos) {
        List<MultipartFile> files = nonEmpty(photos);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens ein Foto mitschicken");
        }
        if (files.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("Höchstens " + MAX_PHOTOS + " Fotos je Beleg");
        }
        Buchung b = new Buchung();
        b.konto = Konto.BARKASSE;
        b.art = Art.AUSGABE;
        b.betrag = new BigDecimal("0.00");
        b.datum = LocalDate.now();
        setStatus(b, statusAfterUpload());
        b.belegFotos = new ArrayList<>(files.stream().map(storage::store).toList());
        return ledger.save(b);
    }

    /** Vollständige Erfassung von Hand (z. B. verlorener Beleg ohne Foto, mit Begründung). Zieht den Betrag sofort ab. */
    @Transactional
    public Buchung submit(ExpenseInput in, List<MultipartFile> photos) {
        Buchung b = new Buchung();
        b.konto = Konto.BARKASSE;
        b.art = Art.AUSGABE;
        setStatus(b, BuchungStatus.EINGEREICHT);
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
        setStatus(b, BuchungStatus.GEBUCHT);
        List<PositionInput> rows = apply(b, in, photos, true);
        Buchung saved = ledger.save(b);
        replacePositions(saved.id, rows);
        return saved;
    }

    /** Nach Ablehnung (Foto unbrauchbar): die erfassende Person lädt neue Fotos hoch, der Beleg wird neu gelesen. */
    @Transactional
    public Buchung replacePhotos(UUID id, List<MultipartFile> photos) {
        Buchung b = find(id);
        if (b.art != Art.AUSGABE || !b.erfasstVon.equals(user.personId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur die erfassende Person kann neue Fotos hochladen");
        }
        if (b.status != BuchungStatus.ABGELEHNT) {
            throw new IllegalStateException("Nur abgelehnte Belege können neu fotografiert werden");
        }
        List<MultipartFile> files = nonEmpty(photos);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Bitte mindestens ein Foto mitschicken");
        }
        if (files.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("Höchstens " + MAX_PHOTOS + " Fotos je Beleg");
        }
        BuchungDto before = mapper.toDto(b);
        b.belegFotos = new ArrayList<>(files.stream().map(storage::store).toList());
        b.begruendungOhneBeleg = null;
        b.ablehnungsKommentar = null;
        b.betrag = new BigDecimal("0.00");
        b.kategorieId = null;
        b.haendler = null;
        b.zweck = null;
        positionen.deleteByBuchungId(b.id);
        resetProcessing(b);
        setStatus(b, statusAfterUpload());
        audit.log("Beleg neu fotografiert", "Buchung", b.id, before, mapper.toDto(b));
        return b;
    }

    private static void resetProcessing(Buchung b) {
        b.verarbeitungVersuche = 0;
        b.verarbeitungFehler = null;
        b.verarbeitungGestartetAm = null;
        b.verarbeitungNaechsterVersuch = null;
    }

    // ---------- Kassenverwalter: Daten erfassen/korrigieren, prüfen

    /** Kassenverwalter trägt die Daten selbst ein oder korrigiert den Vorschlag der KI. Danach wartet der Beleg auf die Prüfung. */
    @Transactional
    public Buchung updateDetails(UUID id, ExpenseInput in) {
        Buchung b = find(id);
        if (b.art != Art.AUSGABE || !EDITABLE.contains(b.status)) {
            throw new IllegalStateException("Diese Ausgabe kann nicht mehr bearbeitet werden");
        }
        BuchungDto before = mapper.toDto(b);
        List<PositionInput> rows = apply(b, in, null, false);
        replacePositions(b.id, rows);
        setStatus(b, BuchungStatus.EINGEREICHT);
        b.verarbeitungFehler = null;
        audit.log("Beleg bearbeitet", "Buchung", b.id, before, mapper.toDto(b));
        return b;
    }

    @Transactional
    public Buchung approve(UUID id) {
        Buchung b = find(id);
        requireSubmitted(b);
        setStatus(b, BuchungStatus.GEPRUEFT);
        b.geprueftVon = user.personId();
        b.geprueftAm = Instant.now();
        audit.log("Ausgabe geprüft", "Buchung", b.id, BuchungStatus.EINGEREICHT, BuchungStatus.GEPRUEFT);
        return b;
    }

    /** Lehnt ab, z. B. weil das Foto unbrauchbar ist. Das Teammitglied lädt dann neue Fotos hoch. */
    @Transactional
    public Buchung reject(UUID id, String kommentar) {
        if (kommentar == null || kommentar.isBlank()) {
            throw new IllegalArgumentException("Kommentar ist Pflicht");
        }
        Buchung b = find(id);
        if (b.art != Art.AUSGABE || !EDITABLE.contains(b.status)) {
            throw new IllegalStateException("Nur offene Belege können abgelehnt werden");
        }
        setStatus(b, BuchungStatus.ABGELEHNT);
        b.ablehnungsKommentar = kommentar.trim();
        audit.log("Ausgabe abgelehnt", "Buchung", b.id, null, kommentar.trim());
        return b;
    }

    // ---------- Hintergrundverarbeitung (vom ReceiptProcessor aufgerufen, je Schritt eigene Transaktion)

    @Transactional(readOnly = true)
    public List<String> photoNamesIfPending(UUID id) {
        return buchungen.findById(id).filter(b -> b.status == BuchungStatus.VERARBEITUNG).map(b -> List.copyOf(b.belegFotos)).orElse(List.of());
    }

    /** Schreibt das Ergebnis der KI an den Beleg. Wurde der Beleg inzwischen anders bearbeitet, passiert nichts. */
    @Transactional
    public void applyRecognition(UUID id, ReceiptSuggestion s) {
        Buchung b = find(id);
        if (b.status != BuchungStatus.VERARBEITUNG) {
            return;
        }
        List<PositionInput> wanted = s.positionen().stream().map(p -> new PositionInput(p.kategorieId(), p.betrag())).toList();
        ExpenseInput in = new ExpenseInput(s.datum(), wanted, s.haendler(), null, null, null);
        List<PositionInput> rows = apply(b, in, null, false);
        replacePositions(b.id, rows);
        setStatus(b, BuchungStatus.EINGEREICHT);
        b.verarbeitungFehler = null;
        recordEndToEnd(b, BuchungStatus.EINGEREICHT);
        audit.log("Beleg von der KI gelesen", "Buchung", b.id, null, mapper.toDto(b));
    }

    /** Vermerkt einen erfolglosen Versuch; nach MAX_ATTEMPTS wird der Beleg zur manuellen Erfassung freigegeben. */
    @Transactional
    public void recordRecognitionFailure(UUID id, String message) {
        Buchung b = find(id);
        if (b.status != BuchungStatus.VERARBEITUNG) {
            return;
        }
        String text = message == null ? "unbekannter Fehler" : (message.length() > 500 ? message.substring(0, 500) : message);
        b.verarbeitungFehler = text;
        b.verarbeitungGestartetAm = null;
        if (b.verarbeitungVersuche >= MAX_ATTEMPTS) {
            setStatus(b, BuchungStatus.MANUELL);
            metrics.aiGaveUp("attempts");
            recordEndToEnd(b, BuchungStatus.MANUELL);
            audit.log("Belegerkennung aufgegeben", "Buchung", b.id, null, text);
        } else {
            b.verarbeitungNaechsterVersuch = Instant.now().plus(Duration.ofSeconds((long) Math.max(1, ai.retryBackoffSeconds()) * Math.max(1, b.verarbeitungVersuche)));
        }
    }

    /** Die KI ist sich bei dem Beleg nicht sicher: sofort zur manuellen Erfassung durch den Kassenverwalter, ohne weitere Versuche. */
    @Transactional
    public void giveUpRecognition(UUID id, String message) {
        Buchung b = find(id);
        if (b.status != BuchungStatus.VERARBEITUNG) {
            return;
        }
        String text = message.length() > 500 ? message.substring(0, 500) : message;
        b.verarbeitungFehler = text;
        b.verarbeitungGestartetAm = null;
        setStatus(b, BuchungStatus.MANUELL);
        metrics.aiGaveUp("uncertain");
        recordEndToEnd(b, BuchungStatus.MANUELL);
        audit.log("Beleg zur manuellen Erfassung freigegeben", "Buchung", b.id, null, text);
    }

    // ---------- Lesen

    @Transactional(readOnly = true)
    public List<BuchungDto> mine(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return mapper.toDtos(buchungen.findByErfasstVonAndArtAndErfasstAmAfterOrderByNummerDesc(user.personId(), Art.AUSGABE, since));
    }

    /** Offene Belege für den Kassenverwalter: in Verarbeitung, manuell zu erfassen oder zur Prüfung. */
    @Transactional(readOnly = true)
    public List<BuchungDto> pending() {
        return mapper.toDtos(buchungen.findByStatusInOrderByNummerAsc(
                List.of(BuchungStatus.VERARBEITUNG, BuchungStatus.MANUELL, BuchungStatus.EINGEREICHT)));
    }

    @Transactional(readOnly = true)
    public List<BuchungDto> search(BuchungStatus status, UUID kategorie, LocalDate von, LocalDate bis, UUID person) {
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

    // ---------- intern

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
        if (in.datum() == null || in.datum().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Das Datum darf nicht fehlen oder in der Zukunft liegen");
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

        List<MultipartFile> files = nonEmpty(photos);
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

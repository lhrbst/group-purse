package de.grouppurse.ledger;

import de.grouppurse.audit.AuditService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.CurrentUser;
import de.grouppurse.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Das Kassenbuch: jede Bewegung ist eine Buchung, nichts wird gelöscht, Fehler werden storniert. */
@Service
public class LedgerService {
    private final BuchungRepository buchungen;
    private final BeitragRepository beitraege;
    private final WohngruppeRepository gruppen;
    private final BookingNumberService numbers;
    private final BalanceService balances;
    private final AuditService audit;
    private final CurrentUser user;

    public LedgerService(BuchungRepository buchungen, BeitragRepository beitraege, WohngruppeRepository gruppen,
                         BookingNumberService numbers, BalanceService balances, AuditService audit,
                         CurrentUser user) {
        this.buchungen = buchungen;
        this.beitraege = beitraege;
        this.gruppen = gruppen;
        this.numbers = numbers;
        this.balances = balances;
        this.audit = audit;
        this.user = user;
    }

    /** Betrag auf höchstens zwei Nachkommastellen prüfen. */
    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Betrag fehlt");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Betrag darf höchstens zwei Nachkommastellen haben");
        }
    }

    private static BigDecimal positive(BigDecimal value) {
        BigDecimal v = money(value);
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("Betrag muss größer als 0 sein");
        }
        return v;
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " ist Pflicht");
        }
        return value.trim();
    }

    /** Legt eine Buchung an (Nummer, Erfasser, Audit). Aufrufer muss in einer Transaktion laufen. */
    @Transactional
    public Buchung book(Konto konto, Art art, BigDecimal signedAmount, LocalDate datum, BuchungStatus status,
                        String zweck, UUID gruppeId) {
        Buchung b = new Buchung();
        b.nummer = numbers.next();
        b.konto = konto;
        b.art = art;
        b.betrag = money(signedAmount);
        b.datum = datum;
        b.status = status;
        b.zweck = zweck;
        b.gruppeId = gruppeId;
        b.erfasstVon = user.personId();
        b.erfasstAm = Instant.now();
        b = buchungen.save(b);
        audit.log("Buchung angelegt", "Buchung", b.id, null, b);
        return b;
    }

    /** Für Buchungen, die ein anderer Service weiter befüllt (z.B. Ausgaben mit Beleg): speichert nach dem Befüllen. */
    @Transactional
    public Buchung save(Buchung b) {
        b.nummer = b.nummer == 0 ? numbers.next() : b.nummer;
        if (b.erfasstVon == null) {
            b.erfasstVon = user.personId();
        }
        Buchung saved = buchungen.save(b);
        audit.log("Buchung angelegt", "Buchung", saved.id, null, saved);
        return saved;
    }

    @Transactional
    public Buchung anfangsbestand(Konto konto, BigDecimal betrag, LocalDate datum) {
        BigDecimal v = money(betrag);
        if (v.signum() < 0) {
            throw new IllegalArgumentException("Anfangsbestand darf nicht negativ sein");
        }
        if (buchungen.existsByKontoAndArtAndStatusNot(konto, Art.ANFANGSBESTAND, BuchungStatus.STORNIERT)) {
            throw new IllegalStateException("Für dieses Konto gibt es bereits einen Anfangsbestand");
        }
        return book(konto, Art.ANFANGSBESTAND, v, datum, BuchungStatus.GEBUCHT, "Anfangsbestand", null);
    }

    /** Vereinskonto − / Barkasse +, als zwei verknüpfte Buchungen. */
    @Transactional
    public List<Buchung> auffuellung(BigDecimal betrag, LocalDate datum, String uebergebenAn) {
        BigDecimal v = positive(betrag);
        String an = required(uebergebenAn, "Übergeben an");
        UUID gruppe = UUID.randomUUID();
        Buchung konto = book(Konto.VEREINSKONTO, Art.AUFFUELLUNG, v.negate(), datum, BuchungStatus.GEBUCHT, "Auffüllung Barkasse, übergeben an " + an, gruppe);
        Buchung kasse = book(Konto.BARKASSE, Art.AUFFUELLUNG, v, datum, BuchungStatus.GEBUCHT, "Auffüllung, übergeben an " + an, gruppe);
        return List.of(konto, kasse);
    }

    /** Barkasse − / Vereinskonto +. */
    @Transactional
    public List<Buchung> rueckgabe(BigDecimal betrag, LocalDate datum, String grund) {
        BigDecimal v = positive(betrag);
        String g = required(grund, "Grund");
        UUID gruppe = UUID.randomUUID();
        Buchung kasse = book(Konto.BARKASSE, Art.RUECKGABE, v.negate(), datum, BuchungStatus.GEBUCHT, "Rückgabe ans Konto: " + g, gruppe);
        Buchung konto = book(Konto.VEREINSKONTO, Art.RUECKGABE, v, datum, BuchungStatus.GEBUCHT, "Rückgabe aus Barkasse: " + g, gruppe);
        return List.of(kasse, konto);
    }

    @Transactional
    public Buchung korrektur(Konto konto, BigDecimal betrag, LocalDate datum, String begruendung) {
        BigDecimal v = money(betrag);
        if (v.signum() == 0) {
            throw new IllegalArgumentException("Eine Korrektur über 0 ist sinnlos");
        }
        return book(konto, Art.KORREKTUR, v, datum, BuchungStatus.GEBUCHT, "Korrektur: " + required(begruendung, "Begründung"), null);
    }

    /**
     * Kassenverwalter trägt den tatsächlichen Stand ein. Erster Eintrag auf einem leeren Konto wird Anfangsbestand,
     * sonst wird die Differenz zum berechneten Stand als Korrektur gebucht.
     */
    @Transactional
    public BestandResult bestandErfassen(Konto konto, BigDecimal istBestand, String begruendung) {
        BigDecimal ist = money(istBestand);
        if (ist.signum() < 0) {
            throw new IllegalArgumentException("Der Bestand darf nicht negativ sein");
        }
        BigDecimal soll = balances.balance(konto);
        boolean firstTime = !buchungen.existsByKonto(konto) && soll.signum() == 0;
        Buchung result = null;
        BigDecimal diff = ist.subtract(soll);
        if (firstTime && diff.signum() != 0) {
            result = book(konto, Art.ANFANGSBESTAND, ist, LocalDate.now(), BuchungStatus.GEBUCHT, "Anfangsbestand", null);
        } else if (diff.signum() != 0) {
            result = korrektur(konto, diff, LocalDate.now(), required(begruendung, "Begründung bei Differenz"));
        }
        Wohngruppe g = gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow();
        g.letzteBestandserfassung = Instant.now();
        audit.log("Bestand erfasst", "Konto", null, soll, ist);
        return new BestandResult(soll, ist, diff, result);
    }

    /** Gegenbuchung(en) zu einer Buchung; verknüpfte Gegenstücke (Auffüllung/Rückgabe) werden mit storniert. */
    @Transactional
    public List<Buchung> storno(UUID buchungId, String begruendung) {
        String reason = required(begruendung, "Begründung");
        Buchung orig = buchungen.findById(buchungId).orElseThrow(() -> new IllegalArgumentException("Buchung nicht gefunden"));
        if (orig.art == Art.STORNO || orig.status == BuchungStatus.STORNIERT) {
            throw new IllegalStateException("Diese Buchung wurde bereits storniert oder ist selbst ein Storno");
        }
        List<Buchung> targets = orig.gruppeId == null ? List.of(orig) : buchungen.findByGruppeId(orig.gruppeId).stream()
                .filter(x -> x.art != Art.STORNO && x.status != BuchungStatus.STORNIERT).toList();
        List<Buchung> result = new ArrayList<>();
        for (Buchung t : targets) {
            BuchungStatus before = t.status;
            t.status = BuchungStatus.STORNIERT;
            Buchung s = new Buchung();
            s.nummer = numbers.next();
            s.konto = t.konto;
            s.art = Art.STORNO;
            s.betrag = t.betrag.negate();
            s.datum = LocalDate.now();
            s.status = BuchungStatus.GEBUCHT;
            s.zweck = "Storno zu Nr. " + t.nummer + ": " + reason;
            s.kategorieId = t.kategorieId;
            s.stornoVon = t.id;
            s.gruppeId = t.gruppeId;
            s.erfasstVon = user.personId();
            s = buchungen.save(s);
            audit.log("Buchung storniert", "Buchung", t.id, before, BuchungStatus.STORNIERT);
            audit.log("Storno angelegt", "Buchung", s.id, null, s);
            result.add(s);
        }
        return result;
    }

}

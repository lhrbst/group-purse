package de.grouppurse.ledger;

import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.ledger.LedgerDtos.AnfangsbestandRequest;
import de.grouppurse.ledger.LedgerDtos.AuffuellungRequest;
import de.grouppurse.ledger.LedgerDtos.BalanceDto;
import de.grouppurse.ledger.LedgerDtos.BestandRequest;
import de.grouppurse.ledger.LedgerDtos.BestandResponse;
import de.grouppurse.ledger.LedgerDtos.KorrekturRequest;
import de.grouppurse.ledger.LedgerDtos.RueckgabeRequest;
import de.grouppurse.ledger.LedgerDtos.StornoRequest;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.Access;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class LedgerController {
    private final Access access;
    private final LedgerService ledger;
    private final BalanceService balances;
    private final BuchungRepository buchungen;
    private final WohngruppeRepository gruppen;
    private final BuchungMapper mapper;

    public LedgerController(Access access, LedgerService ledger, BalanceService balances, BuchungRepository buchungen,
                            WohngruppeRepository gruppen, BuchungMapper mapper) {
        this.access = access;
        this.ledger = ledger;
        this.balances = balances;
        this.buchungen = buchungen;
        this.gruppen = gruppen;
        this.mapper = mapper;
    }

    private static LocalDate dateOrToday(LocalDate d) {
        return d != null ? d : LocalDate.now();
    }

    @PostMapping("/ledger/anfangsbestand")
    public BuchungDto anfangsbestand(@Valid @RequestBody AnfangsbestandRequest r) {
        access.requireKassenverwalter();
        return mapper.toDto(ledger.anfangsbestand(r.konto(), r.betrag(), dateOrToday(r.datum())));
    }

    @PostMapping("/ledger/auffuellung")
    public List<BuchungDto> auffuellung(@Valid @RequestBody AuffuellungRequest r) {
        access.requireKassenverwalter();
        return mapper.toDtos(ledger.auffuellung(r.betrag(), dateOrToday(r.datum()), r.uebergebenAn()));
    }

    @PostMapping("/ledger/rueckgabe")
    public List<BuchungDto> rueckgabe(@Valid @RequestBody RueckgabeRequest r) {
        access.requireKassenverwalter();
        return mapper.toDtos(ledger.rueckgabe(r.betrag(), dateOrToday(r.datum()), r.grund()));
    }

    @PostMapping("/ledger/korrektur")
    public BuchungDto korrektur(@Valid @RequestBody KorrekturRequest r) {
        access.requireKassenverwalter();
        return mapper.toDto(ledger.korrektur(r.konto(), r.betrag(), dateOrToday(r.datum()), r.begruendung()));
    }

    @PostMapping("/ledger/bestand")
    public BestandResponse bestand(@Valid @RequestBody BestandRequest r) {
        access.requireKassenverwalter();
        BestandResult res = ledger.bestandErfassen(r.konto(), r.istBestand(), r.begruendung());
        return new BestandResponse(res.soll(), res.ist(), res.differenz(), res.buchung() == null ? null : mapper.toDto(res.buchung()));
    }

    @PostMapping("/ledger/{id}/storno")
    public List<BuchungDto> storno(@PathVariable UUID id, @RequestBody StornoRequest r) {
        access.requireKassenverwalter();
        return mapper.toDtos(ledger.storno(id, r.begruendung()));
    }

    @GetMapping("/balance")
    @Transactional(readOnly = true)
    public BalanceDto balance() {
        Rolle rolle = access.requireActiveMember();
        Wohngruppe g = gruppen.findById(access.requireGroup()).orElseThrow();
        BigDecimal kasse = balances.balance(Konto.BARKASSE);
        BigDecimal verein = rolle == Rolle.KASSENVERWALTER ? balances.balance(Konto.VEREINSKONTO) : null;
        return new BalanceDto(kasse, verein, g.mindestbetragKasse, kasse.compareTo(g.mindestbetragKasse) < 0, kasse.signum() < 0);
    }

    @GetMapping("/ledger")
    @Transactional(readOnly = true)
    public List<BuchungDto> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis,
                                 @RequestParam(required = false) Konto konto) {
        access.requireKassenverwalter();
        LocalDate from = von != null ? von : LocalDate.of(2000, 1, 1);
        LocalDate to = bis != null ? bis : LocalDate.of(2999, 12, 31);
        var list = konto == null ? buchungen.search(from, to) : buchungen.searchByKonto(konto, from, to);
        return mapper.toDtos(list);
    }
}

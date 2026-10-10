package de.grouppurse.ledger;

import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fortlaufende Buchungsnummern je Wohngruppe; serialisiert über eine Zeilensperre auf der Wohngruppe. */
@Service
public class BookingNumberService {
    private final WohngruppeRepository gruppen;
    private final BuchungRepository buchungen;

    public BookingNumberService(WohngruppeRepository gruppen, BuchungRepository buchungen) {
        this.gruppen = gruppen;
        this.buchungen = buchungen;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next() {
        var tenant = TenantContext.get().orElseThrow(() -> new IllegalStateException("Keine Wohngruppe aktiv"));
        gruppen.findByIdForUpdate(tenant).orElseThrow();
        return buchungen.maxNummer() + 1;
    }
}

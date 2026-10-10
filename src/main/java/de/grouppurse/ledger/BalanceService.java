package de.grouppurse.ledger;

import de.grouppurse.domain.Konto;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.BuchungRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class BalanceService {
    private final BuchungRepository buchungen;
    private final BeitragRepository beitraege;

    public BalanceService(BuchungRepository buchungen, BeitragRepository beitraege) {
        this.buchungen = buchungen;
        this.beitraege = beitraege;
    }

    /** Barkasse: Summe aller Buchungen. Vereinskonto: Summe aller Buchungen plus eingegangene Elternbeiträge. */
    @Transactional(readOnly = true)
    public BigDecimal balance(Konto konto) {
        BigDecimal sum = buchungen.sumByKonto(konto);
        if (konto == Konto.VEREINSKONTO) {
            sum = sum.add(beitraege.sumAll());
        }
        return sum;
    }
}

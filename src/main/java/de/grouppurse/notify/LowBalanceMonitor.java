package de.grouppurse.notify;

import de.grouppurse.domain.Konto;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.ledger.BarkasseChanged;
import de.grouppurse.mail.MailService;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Warnt alle Kassenverwalter einmal, wenn die Barkasse unter den Mindestbetrag fällt. Erst nach einer Auffüllung
 * (Stand wieder über dem Mindestbetrag) kann die Warnung erneut ausgelöst werden.
 */
@Component
public class LowBalanceMonitor {
    private static final Logger log = LoggerFactory.getLogger(LowBalanceMonitor.class);

    private final WohngruppeRepository gruppen;
    private final BalanceService balances;
    private final MitgliedschaftRepository members;
    private final PersonRepository people;
    private final MailService mail;
    private final PushService push;

    public LowBalanceMonitor(WohngruppeRepository gruppen, BalanceService balances, MitgliedschaftRepository members,
                             PersonRepository people, MailService mail, PushService push) {
        this.gruppen = gruppen;
        this.balances = balances;
        this.members = members;
        this.people = people;
        this.mail = mail;
        this.push = push;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBarkasseChanged(BarkasseChanged event) {
        if (event.wohngruppeId() == null) {
            return;
        }
        // läuft im Thread der Anfrage, der Mandant ist noch gesetzt; sonst für die Dauer des Aufrufs setzen
        TenantContext.runAs(event.wohngruppeId(), () -> check(event));
    }

    private void check(BarkasseChanged event) {
        Wohngruppe g = gruppen.findById(event.wohngruppeId()).orElse(null);
        if (g == null) {
            return;
        }
        BigDecimal balance = balances.balance(Konto.BARKASSE);
        boolean low = balance.compareTo(g.mindestbetragKasse) < 0;
        if (!low) {
            g.warnungAktiv = false;
            return;
        }
        if (g.warnungAktiv) {
            return;
        }
        g.warnungAktiv = true;
        NumberFormat eur = NumberFormat.getCurrencyInstance(Locale.GERMANY);
        String text = "In der Barkasse der Wohngruppe \"" + g.name + "\" sind nur noch " + eur.format(balance)
                + " (Mindestbetrag " + eur.format(g.mindestbetragKasse) + "). Bitte die Barkasse auffüllen.";
        for (var m : members.findByWohngruppeIdAndRolleAndStatus(g.id, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV)) {
            Person p = people.findById(m.personId).orElse(null);
            if (p == null) {
                continue;
            }
            try {
                mail.send(p.email, "Gruppenkasse: Barkasse fast leer (" + g.name + ")", text);
            } catch (RuntimeException e) {
                log.warn("Warnmail an {} fehlgeschlagen: {}", p.email, e.toString());
            }
            push.sendTo(p.id, "Barkasse fast leer", text);
        }
    }
}

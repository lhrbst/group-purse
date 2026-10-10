package de.grouppurse.ledger;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.AuditLogRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.WohngruppeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTest extends AbstractIntegrationTest {
    @Autowired LedgerService ledger;
    @Autowired BalanceService balance;
    @Autowired BuchungRepository buchungen;
    @Autowired AuditLogRepository audits;
    @Autowired WohngruppeRepository gruppen;

    Wohngruppe g;
    Person kv;
    final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        g = t.group("Ledger");
        kv = t.person("kv", false);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void auffuellungMovesMoneyBetweenAccounts() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.VEREINSKONTO, bd("1000.00"), today);
            ledger.auffuellung(bd("200.00"), today, "Kasse direkt");
            assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("200.00");
            assertThat(balance.balance(Konto.VEREINSKONTO)).isEqualByComparingTo("800.00");
        });
    }

    @Test
    void rueckgabeMovesMoneyBack() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, bd("300.00"), today);
            ledger.rueckgabe(bd("100.00"), today, "Zu viel Bargeld");
            assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("200.00");
            assertThat(balance.balance(Konto.VEREINSKONTO)).isEqualByComparingTo("100.00");
        });
    }

    @Test
    void stornoCancelsBothLegsAndIsNotRepeatable() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.VEREINSKONTO, bd("1000.00"), today);
            List<Buchung> pair = ledger.auffuellung(bd("200.00"), today, "Paula");
            List<Buchung> storno = ledger.storno(pair.get(1).id, "Falsch gebucht");
            assertThat(storno).hasSize(2).allMatch(s -> s.art == Art.STORNO);
            assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("0.00");
            assertThat(balance.balance(Konto.VEREINSKONTO)).isEqualByComparingTo("1000.00");
            assertThat(buchungen.findById(pair.get(0).id)).get().extracting(b -> b.status).isEqualTo(BuchungStatus.STORNIERT);
            assertThatThrownBy(() -> ledger.storno(pair.get(0).id, "nochmal")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ledger.storno(storno.get(0).id, "storno vom storno")).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void stornoNeedsReason() {
        t.as(kv, g, () -> {
            Buchung b = ledger.anfangsbestand(Konto.BARKASSE, bd("10.00"), today);
            assertThatThrownBy(() -> ledger.storno(b.id, " ")).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void bestandErfassenBooksCorrectionForDifference() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, bd("200.00"), today);
            BestandResult r = ledger.bestandErfassen(Konto.BARKASSE, bd("180.00"), "Zählfehler");
            assertThat(r.soll()).isEqualByComparingTo("200.00");
            assertThat(r.differenz()).isEqualByComparingTo("-20.00");
            assertThat(r.buchung().art).isEqualTo(Art.KORREKTUR);
            assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("180.00");
        });
        assertThat(gruppen.findById(g.id)).get().extracting(x -> x.letzteBestandserfassung).isNotNull();
    }

    @Test
    void bestandErfassenFirstTimeBooksOpeningBalance() {
        t.as(kv, g, () -> {
            BestandResult r = ledger.bestandErfassen(Konto.BARKASSE, bd("150.00"), null);
            assertThat(r.buchung().art).isEqualTo(Art.ANFANGSBESTAND);
            assertThat(balance.balance(Konto.BARKASSE)).isEqualByComparingTo("150.00");
        });
    }

    @Test
    void bestandErfassenWithoutDifferenceBooksNothing() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, bd("50.00"), today);
            BestandResult r = ledger.bestandErfassen(Konto.BARKASSE, bd("50.00"), null);
            assertThat(r.buchung()).isNull();
            assertThat(r.differenz()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void bestandErfassenWithDifferenceNeedsReason() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, bd("50.00"), today);
            assertThatThrownBy(() -> ledger.bestandErfassen(Konto.BARKASSE, bd("40.00"), " "))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void korrekturRequiresReasonAndNonZeroAmount() {
        t.as(kv, g, () -> {
            assertThatThrownBy(() -> ledger.korrektur(Konto.BARKASSE, bd("5.00"), today, "")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ledger.korrektur(Konto.BARKASSE, bd("0.00"), today, "x")).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void amountsWithMoreThanTwoDecimalsAreRejected() {
        t.as(kv, g, () -> assertThatThrownBy(() -> ledger.auffuellung(bd("10.123"), today, "x"))
                .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void numbersAreSequentialPerGroup() {
        Wohngruppe other = t.group("Other");
        t.as(kv, g, () -> ledger.anfangsbestand(Konto.BARKASSE, bd("1.00"), today));
        t.as(kv, other, () -> ledger.anfangsbestand(Konto.BARKASSE, bd("1.00"), today));
        t.as(kv, g, () -> ledger.korrektur(Konto.BARKASSE, bd("1.00"), today, "a"));
        t.as(kv, other, () -> ledger.korrektur(Konto.BARKASSE, bd("1.00"), today, "b"));
        t.as(kv, g, () -> assertThat(buchungen.findAll()).extracting(b -> b.nummer).containsExactlyInAnyOrder(1L, 2L));
        t.as(kv, other, () -> assertThat(buchungen.findAll()).extracting(b -> b.nummer).containsExactlyInAnyOrder(1L, 2L));
    }

    @Test
    void anfangsbestandOnlyOncePerAccount() {
        t.as(kv, g, () -> {
            ledger.anfangsbestand(Konto.BARKASSE, bd("10.00"), today);
            assertThatThrownBy(() -> ledger.anfangsbestand(Konto.BARKASSE, bd("20.00"), today)).isInstanceOf(IllegalStateException.class);
            ledger.anfangsbestand(Konto.VEREINSKONTO, bd("20.00"), today);
        });
    }

    @Test
    void everyBookingIsAudited() {
        t.as(kv, g, () -> {
            Buchung b = ledger.anfangsbestand(Konto.BARKASSE, bd("10.00"), today);
            assertThat(audits.findByEntitaetIdOrderByWannAsc(b.id)).hasSize(1);
            assertThat(audits.findByEntitaetIdOrderByWannAsc(b.id).get(0).wer).isEqualTo(kv.id);
        });
    }
}

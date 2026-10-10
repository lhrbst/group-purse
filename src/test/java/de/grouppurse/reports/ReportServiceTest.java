package de.grouppurse.reports;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.category.CategoryService;
import de.grouppurse.contributions.ContributionDtos.ContributionRequest;
import de.grouppurse.contributions.ContributionDtos.FamilyRequest;
import de.grouppurse.contributions.ContributionService;
import de.grouppurse.contributions.FamilyService;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.expense.ExpenseDtos.ExpenseInput;
import de.grouppurse.expense.ExpenseService;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportServiceTest extends AbstractIntegrationTest {
    @Autowired LedgerService ledger;
    @Autowired ExpenseService expenses;
    @Autowired CategoryService categories;
    @Autowired FamilyService families;
    @Autowired ContributionService contributions;
    @Autowired ReportService reports;
    @Autowired BalanceService balances;

    Wohngruppe g;
    Person kv;
    Person pk;
    UUID lebensmittel;
    UUID haushalt;

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    Buchung spend(Person who, String amount, String date, UUID cat, String bewohner) {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0};
        return t.as(who, g, () -> expenses.submit(new ExpenseInput(bd(amount), LocalDate.parse(date), cat, null, null, bewohner, null),
                List.of(new MockMultipartFile("photos", "b.jpg", "image/jpeg", jpg))));
    }

    @BeforeEach
    void setUp() {
        g = t.group("Reports");
        kv = t.person("kv", false);
        pk = t.person("pk", false);
        t.member(kv, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        t.member(pk, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        t.as(kv, g, () -> {
            lebensmittel = categories.visible().stream().filter(c -> c.name().equals("Lebensmittel")).findFirst().orElseThrow().id();
            haushalt = categories.visible().stream().filter(c -> c.name().equals("Haushalt")).findFirst().orElseThrow().id();
            ledger.anfangsbestand(Konto.BARKASSE, bd("300.00"), LocalDate.of(2026, 1, 1));
            ledger.anfangsbestand(Konto.VEREINSKONTO, bd("1000.00"), LocalDate.of(2026, 1, 1));
            var fam = families.create(new FamilyRequest("Müller", null, null, 5, bd("50.00"), LocalDate.of(2026, 1, 1), null));
            contributions.book(new ContributionRequest(fam.id(), bd("50.00"), LocalDate.of(2026, 1, 5), List.of("2026-01"), null));
            contributions.book(new ContributionRequest(fam.id(), bd("100.00"), LocalDate.of(2026, 2, 3), List.of("2026-02", "2026-03"), null));
        });
        spend(pk, "20.00", "2026-01-10", lebensmittel, "T.");
        Buchung cancelled = spend(kv, "10.00", "2026-01-12", haushalt, null);
        spend(pk, "30.00", "2026-02-10", lebensmittel, null);
        t.as(kv, g, () -> ledger.storno(cancelled.id, "Doppelt erfasst"));
    }

    @Test
    void expensesByCategoryAndMonthIgnoreCancelled() {
        var rows = t.as(kv, g, () -> reports.expensesByCategoryAndMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), null, null, null));
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.monat()).isEqualTo("2026-01");
            assertThat(r.kategorie()).isEqualTo("Lebensmittel");
            assertThat(r.summe()).isEqualByComparingTo("20.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.monat()).isEqualTo("2026-02");
            assertThat(r.summe()).isEqualByComparingTo("30.00");
        });
    }

    @Test
    void filtersByPersonAndResident() {
        var byPk = t.as(kv, g, () -> reports.expensesByCategoryAndMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), pk.id, null, null));
        assertThat(byPk).hasSize(2);
        var byKv = t.as(kv, g, () -> reports.expensesByCategoryAndMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), kv.id, null, null));
        assertThat(byKv).isEmpty();
        var byResident = t.as(kv, g, () -> reports.expensesByCategoryAndMonth(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), null, "t.", null));
        assertThat(byResident).hasSize(1);
        assertThat(byResident.get(0).summe()).isEqualByComparingTo("20.00");
    }

    @Test
    void incomeVersusExpensePerMonth() {
        var rows = t.as(kv, g, () -> reports.incomeVsExpense(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).einnahmen()).isEqualByComparingTo("50.00");
        assertThat(rows.get(0).ausgaben()).isEqualByComparingTo("20.00");
        assertThat(rows.get(1).einnahmen()).isEqualByComparingTo("100.00");
        assertThat(rows.get(1).ausgaben()).isEqualByComparingTo("30.00");
        assertThat(rows.get(2).einnahmen()).isEqualByComparingTo("0");
    }

    @Test
    void balanceHistoryEndsAtCurrentBalance() {
        var points = t.as(kv, g, () -> reports.balanceHistory(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Konto.BARKASSE));
        BigDecimal current = t.as(kv, g, () -> balances.balance(Konto.BARKASSE));
        assertThat(points.get(points.size() - 1).stand()).isEqualByComparingTo(current).isEqualByComparingTo("250.00");
        var bank = t.as(kv, g, () -> reports.balanceHistory(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Konto.VEREINSKONTO));
        assertThat(bank.get(bank.size() - 1).stand()).isEqualByComparingTo("1150.00");
    }

    @Test
    void balanceHistoryStartsWithOpeningBalanceBeforeRange() {
        var points = t.as(kv, g, () -> reports.balanceHistory(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31), Konto.BARKASSE));
        assertThat(points.get(0).datum()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(points.get(0).stand()).isEqualByComparingTo("270.00"); // 300 - 20 - 10 + (-(-10) storno am Stornotag liegt später)
    }

    @Test
    void dashboardsOverHttp() throws Exception {
        MockHttpSession kvs = t.sessionFor(kv, g);
        MockHttpSession pks = t.sessionFor(pk, g);
        mvc.perform(get("/api/dashboard/manager").session(kvs)).andExpect(status().isOk())
                .andExpect(jsonPath("$.barkasse").value(250))
                .andExpect(jsonPath("$.vereinskonto").value(1150))
                .andExpect(jsonPath("$.eingereichtAnzahl").value(2))
                .andExpect(jsonPath("$.letzteBestandserfassung").doesNotExist());
        mvc.perform(get("/api/dashboard/caregiver").session(pks)).andExpect(status().isOk())
                .andExpect(jsonPath("$.barkasse").value(250))
                .andExpect(jsonPath("$.eigeneAusgaben.length()").value(2));
    }

    @Test
    void caregiverCannotSeeManagerReports() throws Exception {
        MockHttpSession pks = t.sessionFor(pk, g);
        mvc.perform(get("/api/dashboard/manager").session(pks)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/expenses-by-category-month").session(pks)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/income-vs-expense").session(pks)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/balance-history").session(pks)).andExpect(status().isForbidden());
    }
}

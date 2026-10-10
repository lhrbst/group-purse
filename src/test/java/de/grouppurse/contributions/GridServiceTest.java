package de.grouppurse.contributions;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.contributions.ContributionDtos.CellStatus;
import de.grouppurse.contributions.ContributionDtos.ContributionRequest;
import de.grouppurse.contributions.ContributionDtos.FamilyRequest;
import de.grouppurse.contributions.ContributionDtos.GridRow;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GridServiceTest extends AbstractIntegrationTest {
    @Autowired FamilyService families;
    @Autowired ContributionService contributions;
    @Autowired GridService grid;

    Wohngruppe g;
    Person kv;
    UUID familie;

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @BeforeEach
    void setUp() {
        g = t.group("Raster");
        kv = t.person("kv", false);
        familie = t.as(kv, g, () -> families.create(new FamilyRequest("Müller", "A.", "mueller@example.org", 5,
                bd("50.00"), LocalDate.of(2026, 1, 1), null)).id());
    }

    void pay(String amount, String date, String... months) {
        t.as(kv, g, () -> contributions.book(new ContributionRequest(familie, bd(amount), LocalDate.parse(date), List.of(months), null)));
    }

    GridRow row(String heute, String von, String bis) {
        return t.as(kv, g, () -> grid.grid(YearMonth.parse(von), YearMonth.parse(bis), LocalDate.parse(heute)).zeilen().get(0));
    }

    CellStatus status(GridRow r, int index) {
        return r.cells().get(index).status();
    }

    @Test
    void fullPaymentMarksMonthPaid() {
        pay("50.00", "2026-01-03", "2026-01");
        GridRow r = row("2026-03-10", "2026-01", "2026-03");
        assertThat(status(r, 0)).isEqualTo(CellStatus.BEZAHLT);
        assertThat(status(r, 1)).isEqualTo(CellStatus.UEBERFAELLIG);
        assertThat(status(r, 2)).isEqualTo(CellStatus.UEBERFAELLIG);
        assertThat(r.ueberfaelligeMonate()).isEqualTo(2);
    }

    @Test
    void doublePaymentCoversTwoMonths() {
        pay("100.00", "2026-01-05", "2026-01", "2026-02");
        GridRow r = row("2026-03-10", "2026-01", "2026-03");
        assertThat(status(r, 0)).isEqualTo(CellStatus.BEZAHLT);
        assertThat(status(r, 1)).isEqualTo(CellStatus.BEZAHLT);
        assertThat(status(r, 2)).isEqualTo(CellStatus.UEBERFAELLIG);
    }

    @Test
    void partialPaymentBeforeDueDateIsPartial() {
        pay("30.00", "2026-01-02", "2026-01");
        GridRow r = row("2026-01-03", "2026-01", "2026-02");
        assertThat(status(r, 0)).isEqualTo(CellStatus.TEILWEISE);
        assertThat(r.cells().get(0).bezahlt()).isEqualByComparingTo("30.00");
        assertThat(status(r, 1)).isEqualTo(CellStatus.OFFEN);
    }

    @Test
    void openBecomesOverdueAfterDueDay() {
        assertThat(status(row("2026-01-05", "2026-01", "2026-01"), 0)).isEqualTo(CellStatus.OFFEN);
        assertThat(status(row("2026-01-06", "2026-01", "2026-01"), 0)).isEqualTo(CellStatus.UEBERFAELLIG);
    }

    @Test
    void changedAmountOnlyAffectsLaterMonths() {
        t.as(kv, g, () -> families.setSollbetrag(familie, bd("60.00"), LocalDate.of(2026, 3, 1)));
        pay("160.00", "2026-01-02", "2026-01", "2026-02", "2026-03");
        GridRow r = row("2026-03-02", "2026-01", "2026-03");
        assertThat(r.cells().get(0).soll()).isEqualByComparingTo("50.00");
        assertThat(r.cells().get(1).soll()).isEqualByComparingTo("50.00");
        assertThat(r.cells().get(2).soll()).isEqualByComparingTo("60.00");
        assertThat(r.cells().stream().map(c -> c.status())).containsOnly(CellStatus.BEZAHLT);
    }

    @Test
    void surplusBecomesCredit() {
        pay("200.00", "2026-01-02", "2026-01", "2026-02", "2026-03");
        GridRow r = row("2026-03-02", "2026-01", "2026-03");
        assertThat(r.cells().stream().map(c -> c.status())).containsOnly(CellStatus.BEZAHLT);
        assertThat(r.guthaben()).isEqualByComparingTo("50.00");
    }

    @Test
    void oldestOpenMonthIsFilledFirstRegardlessOfNote() {
        pay("50.00", "2026-03-01", "2026-03");
        GridRow r = row("2026-03-02", "2026-01", "2026-03");
        assertThat(status(r, 0)).isEqualTo(CellStatus.BEZAHLT);
        assertThat(status(r, 1)).isNotEqualTo(CellStatus.BEZAHLT);
        assertThat(status(r, 2)).isNotEqualTo(CellStatus.BEZAHLT);
    }

    @Test
    void monthsBeforeFirstSollbetragHaveNoSoll() {
        GridRow r = row("2026-03-02", "2025-11", "2026-01");
        assertThat(status(r, 0)).isEqualTo(CellStatus.KEIN_SOLL);
        assertThat(status(r, 1)).isEqualTo(CellStatus.KEIN_SOLL);
        assertThat(status(r, 2)).isNotEqualTo(CellStatus.KEIN_SOLL);
    }

    @Test
    void cancelledContributionNoLongerCounts() {
        pay("50.00", "2026-01-03", "2026-01");
        UUID id = t.as(kv, g, () -> contributions.list(familie).get(0).id());
        t.as(kv, g, () -> contributions.cancel(id, "Falsche Familie"));
        GridRow r = row("2026-01-04", "2026-01", "2026-01");
        assertThat(status(r, 0)).isEqualTo(CellStatus.OFFEN);
    }

    @Test
    void summaryCountsOverdueFamilies() {
        t.as(kv, g, () -> families.create(new FamilyRequest("Schmidt", null, null, 5, bd("40.00"), LocalDate.of(2026, 1, 1), null)));
        pay("50.00", "2026-01-03", "2026-01");
        var s = t.as(kv, g, () -> grid.summary(LocalDate.of(2026, 1, 20)));
        assertThat(s.familien()).isEqualTo(2);
        assertThat(s.ueberfaellig()).isEqualTo(1);
    }
}

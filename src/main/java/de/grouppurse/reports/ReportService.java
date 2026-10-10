package de.grouppurse.reports;

import de.grouppurse.category.CategoryService;
import de.grouppurse.contributions.GridService;
import de.grouppurse.domain.Art;
import de.grouppurse.domain.Beitrag;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.expense.ExpenseService;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.reports.ReportDtos.BalancePoint;
import de.grouppurse.reports.ReportDtos.CategoryMonthSum;
import de.grouppurse.reports.ReportDtos.CaregiverDashboard;
import de.grouppurse.reports.ReportDtos.IncomeExpense;
import de.grouppurse.reports.ReportDtos.ManagerDashboard;
import de.grouppurse.repo.BeitragRepository;
import de.grouppurse.repo.BuchungRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.tenant.TenantContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReportService {
    private final BuchungRepository buchungen;
    private final BeitragRepository beitraege;
    private final WohngruppeRepository gruppen;
    private final BalanceService balances;
    private final GridService grid;
    private final CategoryService categories;
    private final ExpenseService expenses;

    public ReportService(BuchungRepository buchungen, BeitragRepository beitraege, WohngruppeRepository gruppen,
                         BalanceService balances, GridService grid, CategoryService categories, ExpenseService expenses) {
        this.buchungen = buchungen;
        this.beitraege = beitraege;
        this.gruppen = gruppen;
        this.balances = balances;
        this.grid = grid;
        this.categories = categories;
        this.expenses = expenses;
    }

    private Wohngruppe group() {
        return gruppen.findById(TenantContext.get().orElseThrow()).orElseThrow();
    }

    public ManagerDashboard manager(LocalDate heute) {
        Wohngruppe g = group();
        BigDecimal kasse = balances.balance(Konto.BARKASSE);
        return new ManagerDashboard(kasse, balances.balance(Konto.VEREINSKONTO),
                buchungen.countByStatus(BuchungStatus.EINGEREICHT), grid.summary(heute), g.letzteBestandserfassung,
                kasse.compareTo(g.mindestbetragKasse) < 0, kasse.signum() < 0);
    }

    public CaregiverDashboard caregiver() {
        Wohngruppe g = group();
        BigDecimal kasse = balances.balance(Konto.BARKASSE);
        return new CaregiverDashboard(kasse, kasse.compareTo(g.mindestbetragKasse) < 0, kasse.signum() < 0, expenses.mine(30));
    }

    /** Ausgaben (Barkasse und Konto, ohne stornierte) je Monat und Kategorie, als positive Beträge. */
    public List<CategoryMonthSum> expensesByCategoryAndMonth(LocalDate von, LocalDate bis, UUID person, String bewohner, UUID kategorie) {
        Map<UUID, String> names = categories.namesById();
        Map<String, BigDecimal> sums = new TreeMap<>();
        for (Buchung b : expenseRows(von, bis, person, bewohner, kategorie)) {
            String name = b.kategorieId == null ? "Ohne Kategorie" : names.getOrDefault(b.kategorieId, "Ohne Kategorie");
            sums.merge(YearMonth.from(b.datum) + "|" + name, b.betrag.negate(), BigDecimal::add);
        }
        List<CategoryMonthSum> result = new ArrayList<>();
        sums.forEach((k, v) -> {
            String[] parts = k.split("\\|", 2);
            result.add(new CategoryMonthSum(parts[0], parts[1], v));
        });
        return result;
    }

    public List<IncomeExpense> incomeVsExpense(LocalDate von, LocalDate bis) {
        Map<YearMonth, BigDecimal> income = new TreeMap<>();
        Map<YearMonth, BigDecimal> expense = new TreeMap<>();
        for (Beitrag b : beitraege.findByStorniertFalse()) {
            if (!b.eingangsdatum.isBefore(von) && !b.eingangsdatum.isAfter(bis)) {
                income.merge(YearMonth.from(b.eingangsdatum), b.betrag, BigDecimal::add);
            }
        }
        for (Buchung b : expenseRows(von, bis, null, null, null)) {
            expense.merge(YearMonth.from(b.datum), b.betrag.negate(), BigDecimal::add);
        }
        List<IncomeExpense> result = new ArrayList<>();
        for (YearMonth m = YearMonth.from(von); !m.isAfter(YearMonth.from(bis)); m = m.plusMonths(1)) {
            result.add(new IncomeExpense(m.toString(), income.getOrDefault(m, BigDecimal.ZERO), expense.getOrDefault(m, BigDecimal.ZERO)));
        }
        return result;
    }

    /** Stand zu Beginn des Zeitraums plus ein Punkt je Tag mit Bewegung. */
    public List<BalancePoint> balanceHistory(LocalDate von, LocalDate bis, Konto konto) {
        Map<LocalDate, BigDecimal> deltas = new TreeMap<>();
        for (Buchung b : buchungen.findAll()) {
            if (b.konto == konto) {
                deltas.merge(b.datum, b.betrag, BigDecimal::add);
            }
        }
        if (konto == Konto.VEREINSKONTO) {
            for (Beitrag b : beitraege.findByStorniertFalse()) {
                deltas.merge(b.eingangsdatum, b.betrag, BigDecimal::add);
            }
        }
        BigDecimal running = BigDecimal.ZERO;
        List<BalancePoint> points = new ArrayList<>();
        for (var e : deltas.entrySet()) {
            if (e.getKey().isBefore(von)) {
                running = running.add(e.getValue());
            }
        }
        points.add(new BalancePoint(von, running));
        for (var e : deltas.entrySet()) {
            if (!e.getKey().isBefore(von) && !e.getKey().isAfter(bis)) {
                running = running.add(e.getValue());
                points.add(new BalancePoint(e.getKey(), running));
            }
        }
        points.sort(Comparator.comparing(BalancePoint::datum));
        return points;
    }

    private List<Buchung> expenseRows(LocalDate von, LocalDate bis, UUID person, String bewohner, UUID kategorie) {
        Specification<Buchung> spec = (r, q, cb) -> cb.and(
                r.get("art").in(Art.AUSGABE, Art.KONTOAUSGABE),
                cb.notEqual(r.get("status"), BuchungStatus.STORNIERT),
                cb.greaterThanOrEqualTo(r.<LocalDate>get("datum"), von),
                cb.lessThanOrEqualTo(r.<LocalDate>get("datum"), bis));
        if (person != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("erfasstVon"), person));
        }
        if (kategorie != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("kategorieId"), kategorie));
        }
        if (bewohner != null && !bewohner.isBlank()) {
            String b = bewohner.trim().toLowerCase();
            spec = spec.and((r, q, cb) -> cb.equal(cb.lower(r.get("bewohnerKuerzel")), b));
        }
        return buchungen.findAll(spec);
    }
}

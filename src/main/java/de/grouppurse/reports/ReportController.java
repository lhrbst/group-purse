package de.grouppurse.reports;

import de.grouppurse.domain.Konto;
import de.grouppurse.reports.ReportDtos.BalancePoint;
import de.grouppurse.reports.ReportDtos.CategoryMonthSum;
import de.grouppurse.reports.ReportDtos.CaregiverDashboard;
import de.grouppurse.reports.ReportDtos.IncomeExpense;
import de.grouppurse.reports.ReportDtos.ManagerDashboard;
import de.grouppurse.security.Access;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final Access access;
    private final ReportService reports;

    public ReportController(Access access, ReportService reports) {
        this.access = access;
        this.reports = reports;
    }

    private static LocalDate from(LocalDate von) {
        return von != null ? von : LocalDate.now().withDayOfMonth(1).minusMonths(11);
    }

    private static LocalDate to(LocalDate bis) {
        return bis != null ? bis : LocalDate.now();
    }

    @GetMapping("/dashboard/manager")
    public ManagerDashboard manager() {
        access.requireKassenverwalter();
        return reports.manager(LocalDate.now());
    }

    @GetMapping("/dashboard/caregiver")
    public CaregiverDashboard caregiver() {
        access.requireActiveMember();
        return reports.caregiver();
    }

    @GetMapping("/reports/expenses-by-category-month")
    public List<CategoryMonthSum> byCategory(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis,
                                             @RequestParam(required = false) UUID person,
                                             @RequestParam(required = false) String bewohner,
                                             @RequestParam(required = false) UUID kategorie,
                                             @RequestParam(defaultValue = "UNTER") String ebene) {
        access.requireKassenverwalter();
        return reports.expensesByCategoryAndMonth(from(von), to(bis), person, bewohner, kategorie, "HAUPT".equalsIgnoreCase(ebene));
    }

    @GetMapping("/reports/income-vs-expense")
    public List<IncomeExpense> incomeVsExpense(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis) {
        access.requireKassenverwalter();
        return reports.incomeVsExpense(from(von), to(bis));
    }

    @GetMapping("/reports/balance-history")
    public List<BalancePoint> balanceHistory(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate von,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bis,
                                             @RequestParam(defaultValue = "BARKASSE") Konto konto) {
        access.requireKassenverwalter();
        return reports.balanceHistory(from(von), to(bis), konto);
    }
}

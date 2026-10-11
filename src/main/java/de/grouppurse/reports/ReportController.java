package de.grouppurse.reports;

import de.grouppurse.domain.Konto;
import de.grouppurse.metrics.GkMetrics;
import de.grouppurse.reports.ReportDtos.BalancePoint;
import de.grouppurse.reports.ReportDtos.CategoryMonthSum;
import de.grouppurse.reports.ReportDtos.CaregiverDashboard;
import de.grouppurse.reports.ReportDtos.IncomeExpense;
import de.grouppurse.reports.ReportDtos.ManagerDashboard;
import de.grouppurse.reports.ReportDtos.MonthlyReport;
import de.grouppurse.security.Access;
import de.grouppurse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final Access access;
    private final ReportService reports;
    private final MonthlyReportService monthly;
    private final MonthlyReportPdf pdfs;
    private final GkMetrics metrics;
    private final CurrentUser user;

    public ReportController(Access access, ReportService reports, MonthlyReportService monthly, MonthlyReportPdf pdfs,
                            GkMetrics metrics, CurrentUser user) {
        this.access = access;
        this.reports = reports;
        this.monthly = monthly;
        this.pdfs = pdfs;
        this.metrics = metrics;
        this.user = user;
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

    private static YearMonth month(String monat) {
        YearMonth m;
        try {
            m = YearMonth.parse(monat);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Monat, erwartet JJJJ-MM");
        }
        if (m.isAfter(YearMonth.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Der Monat liegt in der Zukunft");
        }
        return m;
    }

    @GetMapping("/reports/monthly")
    public MonthlyReport monthlyReport(@RequestParam String monat) {
        access.requireKassenverwalter();
        return monthly.report(month(monat));
    }

    /** Download-Link der Oberfläche, deshalb nicht im generierten Client. */
    @Hidden
    @GetMapping(value = "/reports/monthly.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> monthlyPdf(@RequestParam String monat) {
        access.requireKassenverwalter();
        YearMonth m = month(monat);
        try {
            byte[] pdf = pdfs.render(monthly.report(m), user.principal().orElseThrow().name(), LocalDate.now());
            metrics.reportPdf("ok");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Gruppenkasse-" + m + ".pdf\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(pdf);
        } catch (RuntimeException e) {
            metrics.reportPdf("error");
            throw e;
        }
    }
}

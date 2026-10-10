package de.grouppurse.reports;

import de.grouppurse.contributions.ContributionDtos.Summary;
import de.grouppurse.ledger.BuchungDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportDtos {
    private ReportDtos() {
    }

    public record ManagerDashboard(BigDecimal barkasse, BigDecimal vereinskonto, long eingereichtAnzahl,
                                   Summary offeneBeitraege, Instant letzteBestandserfassung, boolean warnung, boolean negativ,
                                   long ohneBetrag) {
    }

    public record CaregiverDashboard(BigDecimal barkasse, boolean warnung, boolean negativ, long ohneBetrag, List<BuchungDto> eigeneAusgaben) {
    }

    public record CategoryMonthSum(String monat, String kategorie, BigDecimal summe) {
    }

    public record IncomeExpense(String monat, BigDecimal einnahmen, BigDecimal ausgaben) {
    }

    public record BalancePoint(LocalDate datum, BigDecimal stand) {
    }
}

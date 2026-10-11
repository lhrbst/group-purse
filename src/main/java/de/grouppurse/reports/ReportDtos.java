package de.grouppurse.reports;

import de.grouppurse.contributions.ContributionDtos.Summary;
import de.grouppurse.domain.Konto;
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

    public record MonthlyReport(String monat, String gruppe, BigDecimal summe, int belegAnzahl, BigDecimal vormonatSumme,
                                BigDecimal aenderung, BigDecimal aenderungProzent, int ungeprueftAnzahl, BigDecimal ungeprueftSumme,
                                String zusammenfassung, List<MonthlyCategory> kategorien, List<MonthlyBeleg> belege) {
    }

    /** unterkategorie ist null, wenn die Positionen direkt auf der Hauptkategorie liegen; anteilProzent ist 0,0 bei Summe 0. */
    public record MonthlyCategory(String hauptkategorie, String unterkategorie, BigDecimal betrag, BigDecimal anteilProzent) {
    }

    public record MonthlyBeleg(LocalDate datum, long nummer, Konto konto, String haendler, String erfasstVon, BigDecimal betrag,
                               boolean zuPruefen, List<MonthlyPosition> positionen) {
    }

    public record MonthlyPosition(String kategorie, BigDecimal betrag) {
    }
}

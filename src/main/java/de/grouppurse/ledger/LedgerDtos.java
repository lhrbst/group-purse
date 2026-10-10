package de.grouppurse.ledger;

import de.grouppurse.domain.Konto;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class LedgerDtos {
    private LedgerDtos() {
    }

    public record AnfangsbestandRequest(@NotNull Konto konto, @NotNull BigDecimal betrag, LocalDate datum) {
    }

    public record AuffuellungRequest(@NotNull BigDecimal betrag, LocalDate datum, String uebergebenAn) {
    }

    public record RueckgabeRequest(@NotNull BigDecimal betrag, LocalDate datum, String grund) {
    }

    public record KorrekturRequest(@NotNull Konto konto, @NotNull BigDecimal betrag, LocalDate datum, String begruendung) {
    }

    public record BestandRequest(@NotNull Konto konto, @NotNull BigDecimal istBestand, String begruendung) {
    }

    public record StornoRequest(String begruendung) {
    }

    public record BestandResponse(BigDecimal soll, BigDecimal ist, BigDecimal differenz, BuchungDto buchung) {
    }

    /** vereinskonto ist für Pflegekräfte null. */
    public record BalanceDto(BigDecimal barkasse, BigDecimal vereinskonto, BigDecimal mindestbetrag, boolean warnung, boolean negativ) {
    }
}

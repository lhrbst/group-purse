package de.grouppurse.expense;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class ExpenseDtos {
    private ExpenseDtos() {
    }

    public record ExpenseInput(@NotNull BigDecimal betrag, @NotNull LocalDate datum, @NotNull UUID kategorieId,
                               String haendler, String zweck, String bewohnerKuerzel, String begruendungOhneBeleg) {
    }

    public record RejectRequest(String kommentar) {
    }
}

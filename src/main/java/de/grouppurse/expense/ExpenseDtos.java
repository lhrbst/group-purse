package de.grouppurse.expense;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ExpenseDtos {
    private ExpenseDtos() {
    }

    /** Eine Zeile des Belegs. Der Gesamtbetrag der Ausgabe ist die Summe aller Positionen. */
    public record PositionInput(@NotNull UUID kategorieId, @NotNull BigDecimal betrag) {
    }

    public record ExpenseInput(@NotNull LocalDate datum, @NotEmpty @Valid List<PositionInput> positionen,
                               String haendler, String zweck, String bewohnerKuerzel, String begruendungOhneBeleg) {
        /** Beleg mit nur einer Position. */
        public static ExpenseInput single(BigDecimal betrag, LocalDate datum, UUID kategorieId, String haendler, String zweck,
                                          String bewohnerKuerzel, String begruendungOhneBeleg) {
            return new ExpenseInput(datum, List.of(new PositionInput(kategorieId, betrag)), haendler, zweck, bewohnerKuerzel, begruendungOhneBeleg);
        }
    }

    public record RejectRequest(String kommentar) {
    }
}

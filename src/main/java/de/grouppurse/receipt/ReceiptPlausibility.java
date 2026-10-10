package de.grouppurse.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ReceiptPlausibility {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private ReceiptPlausibility() {
    }

    /**
     * Händler, Datum und Betrag gesetzt, Datum nicht in der Zukunft, Betrag größer 0 und kleiner als der Kassenstand;
     * mindestens eine Position, jede mit bekannter Kategorie und Betrag größer 0, und die Summe der Positionen entspricht der Endsumme.
     */
    public static boolean isPlausible(ReceiptSuggestion s, LocalDate today, BigDecimal kassenstand) {
        if (s == null || s.haendler() == null || s.haendler().isBlank()) {
            return false;
        }
        if (s.datum() == null || s.datum().isAfter(today)) {
            return false;
        }
        if (s.betrag() == null || s.betrag().signum() <= 0 || s.betrag().compareTo(kassenstand) >= 0) {
            return false;
        }
        if (s.positionen() == null || s.positionen().isEmpty()) {
            return false;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ReceiptSuggestion.PositionSuggestion p : s.positionen()) {
            if (p.kategorieId() == null || p.betrag() == null || p.betrag().signum() <= 0) {
                return false;
            }
            sum = sum.add(p.betrag());
        }
        return sum.subtract(s.betrag()).abs().compareTo(TOLERANCE) <= 0;
    }
}

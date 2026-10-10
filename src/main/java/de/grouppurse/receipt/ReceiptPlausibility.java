package de.grouppurse.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class ReceiptPlausibility {
    private ReceiptPlausibility() {
    }

    /** Alle Felder gesetzt, Datum nicht in der Zukunft, Betrag größer 0 und kleiner als der Kassenstand. */
    public static boolean isPlausible(ReceiptSuggestion s, LocalDate today, BigDecimal kassenstand) {
        if (s == null || s.haendler() == null || s.haendler().isBlank() || s.kategorieId() == null) {
            return false;
        }
        if (s.datum() == null || s.datum().isAfter(today)) {
            return false;
        }
        return s.betrag() != null && s.betrag().signum() > 0 && s.betrag().compareTo(kassenstand) < 0;
    }
}

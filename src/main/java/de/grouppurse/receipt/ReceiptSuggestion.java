package de.grouppurse.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Vorschlag aus der Belegerkennung; alle Felder können fehlen. */
public record ReceiptSuggestion(String haendler, LocalDate datum, BigDecimal betrag, UUID kategorieId) {
}

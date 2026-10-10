package de.grouppurse.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Vorschlag aus der Belegerkennung; alle Felder können fehlen. betrag ist die Endsumme laut Bon, die Positionen sind die Aufteilung. */
public record ReceiptSuggestion(String haendler, LocalDate datum, BigDecimal betrag, List<PositionSuggestion> positionen) {

    /** kategorieId ist leer, wenn das Modell eine unbekannte Kategorie genannt hat. */
    public record PositionSuggestion(UUID kategorieId, BigDecimal betrag) {
    }
}

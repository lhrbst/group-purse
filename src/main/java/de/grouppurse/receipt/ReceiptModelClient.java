package de.grouppurse.receipt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Schmale Schnittstelle zum KI-Dienst, damit Logik und Aufruf getrennt testbar sind. */
public interface ReceiptModelClient {
    /** Rohwerte des Modells für einen Beleg (ggf. mehrere Fotos); Optional.empty bei Fehler oder Timeout. */
    Optional<RawReceipt> extract(List<ReceiptImage> images, List<String> categoryNames, String model);

    /** Eine Kategorie samt Summe aller Bonzeilen, die dazugehören. */
    record RawPosition(String kategorie, BigDecimal betrag) {
    }

    record RawReceipt(String haendler, String datum, BigDecimal gesamtbetrag, List<RawPosition> positionen) {
    }
}

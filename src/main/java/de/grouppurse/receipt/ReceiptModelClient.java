package de.grouppurse.receipt;

import java.util.List;
import java.util.Optional;

/** Schmale Schnittstelle zum KI-Dienst, damit Logik und Aufruf getrennt testbar sind. */
public interface ReceiptModelClient {
    /** Rohwerte des Modells; Optional.empty bei Fehler oder Timeout. */
    Optional<RawReceipt> extract(byte[] image, String mimeType, List<String> categoryNames, String model);

    record RawReceipt(String haendler, String datum, java.math.BigDecimal gesamtbetrag, String kategorie) {
    }
}

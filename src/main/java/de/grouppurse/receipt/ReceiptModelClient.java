package de.grouppurse.receipt;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

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

    /**
     * Antwort des Modells. Ein Foto kann mehrere Bons zeigen: gesamtbetrag und positionen gelten dann für alle zusammen.
     * sicher = false heißt, dass das Modell den Beleg nicht verlässlich lesen kann (unsicherheit sagt warum); dann trägt ihn der
     * Kassenverwalter von Hand ein.
     */
    record RawReceipt(
            @JsonPropertyDescription("Anzahl der verschiedenen Bons auf den Fotos") Integer belegAnzahl,
            @JsonPropertyDescription("Name des Geschäfts, bei mehreren Bons alle Namen kommagetrennt") String haendler,
            @JsonPropertyDescription("Kaufdatum JJJJ-MM-TT, bei mehreren Bons das späteste") String datum,
            @JsonPropertyDescription("Summe der Endbeträge aller Bons in Euro") BigDecimal gesamtbetrag,
            @JsonPropertyDescription("Aufteilung aller Bons zusammen nach Kategorie") List<RawPosition> positionen,
            @JsonPropertyDescription("false, wenn ein Bon, ein Betrag oder eine Zeile nicht sicher lesbar ist") Boolean sicher,
            @JsonPropertyDescription("Wenn sicher=false: kurze Begründung auf Deutsch") String unsicherheit) {

        /** Kurzform für einen einzelnen, sicher gelesenen Bon (Tests, einfache Aufrufer). */
        public RawReceipt(String haendler, String datum, BigDecimal gesamtbetrag, List<RawPosition> positionen) {
            this(1, haendler, datum, gesamtbetrag, positionen, Boolean.TRUE, null);
        }

        public boolean isUncertain() {
            return Boolean.FALSE.equals(sicher);
        }
    }
}

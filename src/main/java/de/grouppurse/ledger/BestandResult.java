package de.grouppurse.ledger;

import de.grouppurse.domain.Buchung;

import java.math.BigDecimal;

/** Ergebnis von "Bestand erfassen": berechneter Stand, eingegebener Stand, Differenz und ggf. die gebuchte Korrektur. */
public record BestandResult(BigDecimal soll, BigDecimal ist, BigDecimal differenz, Buchung buchung) {
}

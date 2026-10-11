package de.grouppurse.receipt;

/** Das Modell hat den Beleg als nicht verlässlich lesbar gemeldet; er muss manuell erfasst werden (kein weiterer Versuch). */
public class ReceiptUncertainException extends RuntimeException {
    public ReceiptUncertainException(String reason) {
        super(reason);
    }
}

package de.grouppurse.receipt;

/** Ein Belegfoto, das an die KI geschickt wird. */
public record ReceiptImage(byte[] bytes, String mimeType) {
}

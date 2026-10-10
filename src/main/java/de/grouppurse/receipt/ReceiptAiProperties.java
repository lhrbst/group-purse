package de.grouppurse.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Modellnamen und Schalter sind Konfiguration, damit der Betrieb ohne Codeänderung umstellen oder abschalten kann. */
@ConfigurationProperties("app.receipt")
public record ReceiptAiProperties(boolean enabled, String model, String fallbackModel) {
}

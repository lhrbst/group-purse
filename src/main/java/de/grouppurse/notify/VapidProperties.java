package de.grouppurse.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.vapid")
public record VapidProperties(String publicKey, String privateKey, String subject) {
    public boolean configured() {
        return publicKey != null && !publicKey.isBlank() && privateKey != null && !privateKey.isBlank();
    }
}

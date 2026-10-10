package de.grouppurse.notify;

import de.grouppurse.domain.PushSubscription;
import de.grouppurse.repo.PushSubscriptionRepository;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Web-Push ohne externen Dienst (VAPID). Ohne Schlüssel ist Push abgeschaltet und alle Aufrufe sind wirkungslos. */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final PushSubscriptionRepository subscriptions;
    private final VapidProperties vapid;
    private final JsonMapper json;
    private nl.martijndwars.webpush.PushService client;

    public PushService(PushSubscriptionRepository subscriptions, VapidProperties vapid, JsonMapper json) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.json = json;
    }

    public boolean enabled() {
        return vapid.configured();
    }

    public String publicKey() {
        return vapid.configured() ? vapid.publicKey() : "";
    }

    private synchronized nl.martijndwars.webpush.PushService client() throws Exception {
        if (client == null) {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            client = new nl.martijndwars.webpush.PushService(vapid.publicKey(), vapid.privateKey(), vapid.subject());
        }
        return client;
    }

    /** Sendet an alle registrierten Geräte der Person in der aktiven Wohngruppe; ungültige Registrierungen werden entfernt. */
    public void sendTo(UUID personId, String title, String body) {
        if (!enabled()) {
            return;
        }
        List<PushSubscription> subs = subscriptions.findByPersonId(personId);
        for (PushSubscription s : subs) {
            try {
                String payload = json.writeValueAsString(Map.of("title", title, "body", body, "url", "/"));
                var response = client().send(new Notification(s.endpoint, s.p256dh, s.auth, payload.getBytes(StandardCharsets.UTF_8)));
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    subscriptions.delete(s);
                } else if (status >= 300) {
                    log.warn("Push an {} lieferte Status {}", s.endpoint, status);
                }
            } catch (Exception e) {
                log.warn("Push fehlgeschlagen: {}", e.toString());
            }
        }
    }
}

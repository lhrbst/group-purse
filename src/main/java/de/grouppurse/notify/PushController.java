package de.grouppurse.notify;

import de.grouppurse.domain.PushSubscription;
import de.grouppurse.repo.PushSubscriptionRepository;
import de.grouppurse.security.Access;
import de.grouppurse.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {
    public record PublicKey(String publicKey) {
    }

    public record SubscribeRequest(@NotBlank String endpoint, @NotBlank String p256dh, @NotBlank String auth) {
    }

    private final Access access;
    private final CurrentUser user;
    private final PushService push;
    private final PushSubscriptionRepository subscriptions;

    public PushController(Access access, CurrentUser user, PushService push, PushSubscriptionRepository subscriptions) {
        this.access = access;
        this.user = user;
        this.push = push;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/public-key")
    public PublicKey publicKey() {
        return new PublicKey(push.publicKey());
    }

    @PostMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void subscribe(@Valid @RequestBody SubscribeRequest r) {
        access.requireActiveMember();
        PushSubscription s = subscriptions.findByPersonIdAndEndpoint(user.personId(), r.endpoint()).orElseGet(PushSubscription::new);
        s.personId = user.personId();
        s.endpoint = r.endpoint();
        s.p256dh = r.p256dh();
        s.auth = r.auth();
        subscriptions.save(s);
    }

    @DeleteMapping("/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unsubscribe(@RequestParam String endpoint) {
        access.requireActiveMember();
        subscriptions.findByPersonIdAndEndpoint(user.personId(), endpoint).ifPresent(subscriptions::delete);
    }
}

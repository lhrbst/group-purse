package de.grouppurse.auth;

import de.grouppurse.domain.PasswortResetToken;
import de.grouppurse.repo.PasswortResetTokenRepository;
import de.grouppurse.repo.PersonRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Passwort-Reset ohne E-Mail: Ein Kassenverwalter oder Admin erzeugt einen Link und gibt ihn persönlich weiter. */
@Service
public class PasswordResetService {
    private static final Duration VALIDITY = Duration.ofHours(24);

    private final PersonRepository people;
    private final PasswortResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final String baseUrl;

    public PasswordResetService(PersonRepository people, PasswortResetTokenRepository tokens, PasswordEncoder encoder,
                                @Value("${app.base-url}") String baseUrl) {
        this.people = people;
        this.tokens = tokens;
        this.encoder = encoder;
        this.baseUrl = baseUrl;
    }

    /** Erzeugt einen einmaligen Link (24 Stunden gültig). In der Datenbank liegt nur der Hash des Tokens. */
    @Transactional
    public String createLink(UUID personId) {
        people.findById(personId).orElseThrow(() -> new IllegalArgumentException("Person nicht gefunden"));
        String token = TokenUtil.newToken();
        PasswortResetToken t = new PasswortResetToken();
        t.personId = personId;
        t.tokenHash = TokenUtil.hash(token);
        t.gueltigBis = Instant.now().plus(VALIDITY);
        tokens.save(t);
        return baseUrl + "/passwort-neu/" + token;
    }

    @Transactional
    public void confirm(String token, String newPassword) {
        PasswortResetToken t = tokens.findByTokenHash(TokenUtil.hash(token))
                .filter(x -> x.eingeloestAm == null && x.gueltigBis.isAfter(Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("Link ungültig oder abgelaufen"));
        var person = people.findById(t.personId).orElseThrow();
        person.passwortHash = encoder.encode(newPassword);
        t.eingeloestAm = Instant.now();
    }
}

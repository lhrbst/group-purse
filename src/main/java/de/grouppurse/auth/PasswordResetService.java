package de.grouppurse.auth;

import de.grouppurse.domain.PasswortResetToken;
import de.grouppurse.mail.MailService;
import de.grouppurse.repo.PasswortResetTokenRepository;
import de.grouppurse.repo.PersonRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class PasswordResetService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PersonRepository people;
    private final PasswortResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final String baseUrl;

    public PasswordResetService(PersonRepository people, PasswortResetTokenRepository tokens, PasswordEncoder encoder,
                                MailService mail, @Value("${app.base-url}") String baseUrl) {
        this.people = people;
        this.tokens = tokens;
        this.encoder = encoder;
        this.mail = mail;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public void request(String email) {
        people.findByEmail(email.trim().toLowerCase(Locale.ROOT)).ifPresent(p -> {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            PasswortResetToken t = new PasswortResetToken();
            t.personId = p.id;
            t.tokenHash = hash(token);
            t.gueltigBis = Instant.now().plus(Duration.ofHours(1));
            tokens.save(t);
            mail.send(p.email, "Gruppenkasse: Passwort zurücksetzen",
                    "Hallo " + p.name + ",\n\nüber diesen Link kannst du ein neues Passwort setzen (1 Stunde gültig):\n"
                            + baseUrl + "/passwort-neu/" + token + "\n\nWenn du das nicht angefordert hast, ignoriere diese Mail.");
        });
    }

    @Transactional
    public void confirm(String token, String newPassword) {
        PasswortResetToken t = tokens.findByTokenHash(hash(token))
                .filter(x -> x.eingeloestAm == null && x.gueltigBis.isAfter(Instant.now()))
                .orElseThrow(() -> new IllegalArgumentException("Link ungültig oder abgelaufen"));
        var person = people.findById(t.personId).orElseThrow();
        person.passwortHash = encoder.encode(newPassword);
        t.eingeloestAm = Instant.now();
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

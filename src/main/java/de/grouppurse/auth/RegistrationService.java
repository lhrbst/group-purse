package de.grouppurse.auth;

import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;

/** Selbstregistrierung von Teammitgliedern per Beitrittscode. */
@Service
public class RegistrationService {
    private final PersonRepository people;
    private final MitgliedschaftRepository members;
    private final WohngruppeRepository gruppen;
    private final PasswordEncoder encoder;

    public RegistrationService(PersonRepository people, MitgliedschaftRepository members,
                               WohngruppeRepository gruppen, PasswordEncoder encoder) {
        this.people = people;
        this.members = members;
        this.gruppen = gruppen;
        this.encoder = encoder;
    }

    @Transactional
    public Person register(RegisterRequest r) {
        if (!Boolean.TRUE.equals(r.datenschutzZugestimmt())) {
            throw new IllegalArgumentException("Der Datenschutzhinweis muss bestätigt werden");
        }
        Wohngruppe g = gruppen.findByBeitrittscode(r.beitrittscode().trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Beitrittscode ungültig"));
        String email = AuthService.normalizeEmail(r.email());
        Person p = people.findByEmail(email).orElse(null);
        if (p == null) {
            if (r.password().length() < 10) {
                throw new IllegalArgumentException("Passwort braucht mindestens 10 Zeichen");
            }
            p = new Person();
            p.name = r.name().trim();
            p.email = email;
            p.passwortHash = encoder.encode(r.password());
            p.datenschutzZugestimmtAm = Instant.now();
            p = people.save(p);
        } else if (!encoder.matches(r.password(), p.passwortHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Diese E-Mail ist bereits registriert. Bitte das bestehende Passwort verwenden.");
        }
        if (members.findByPersonIdAndWohngruppeId(p.id, g.id).isPresent()) {
            throw new IllegalStateException("Du bist dieser Wohngruppe bereits zugeordnet");
        }
        Mitgliedschaft m = new Mitgliedschaft();
        m.personId = p.id;
        m.wohngruppeId = g.id;
        m.rolle = Rolle.PFLEGEKRAFT;
        m.status = MitgliedStatus.WARTEND;
        members.save(m);
        return p;
    }
}

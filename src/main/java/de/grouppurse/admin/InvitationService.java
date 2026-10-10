package de.grouppurse.admin;

import de.grouppurse.admin.AdminDtos.InvitationInfo;
import de.grouppurse.admin.AdminDtos.InviteResponse;
import de.grouppurse.admin.AdminDtos.RedeemRequest;
import de.grouppurse.auth.AuthService;
import de.grouppurse.auth.TokenUtil;
import de.grouppurse.domain.Einladung;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.mail.MailService;
import de.grouppurse.repo.EinladungRepository;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class InvitationService {
    private final EinladungRepository einladungen;
    private final WohngruppeRepository gruppen;
    private final PersonRepository people;
    private final MitgliedschaftRepository members;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final CurrentUser user;
    private final String baseUrl;

    public InvitationService(EinladungRepository einladungen, WohngruppeRepository gruppen, PersonRepository people,
                             MitgliedschaftRepository members, PasswordEncoder encoder, MailService mail,
                             CurrentUser user, @Value("${app.base-url}") String baseUrl) {
        this.einladungen = einladungen;
        this.gruppen = gruppen;
        this.people = people;
        this.members = members;
        this.encoder = encoder;
        this.mail = mail;
        this.user = user;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public InviteResponse inviteKassenverwalter(UUID wohngruppeId, String rawEmail) {
        Wohngruppe g = gruppen.findById(wohngruppeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wohngruppe nicht gefunden"));
        String email = AuthService.normalizeEmail(rawEmail);
        people.findByEmail(email).flatMap(p -> members.findByPersonIdAndWohngruppeId(p.id, g.id))
                .filter(m -> m.rolle == Rolle.KASSENVERWALTER && m.status == MitgliedStatus.AKTIV)
                .ifPresent(m -> {
                    throw new IllegalStateException("Diese Person ist bereits Kassenverwalter dieser Wohngruppe");
                });
        String token = TokenUtil.newToken();
        Einladung e = new Einladung();
        e.token = TokenUtil.hash(token);
        e.email = email;
        e.wohngruppeId = g.id;
        e.rolle = Rolle.KASSENVERWALTER;
        e.gueltigBis = Instant.now().plus(Duration.ofDays(7));
        einladungen.save(e);
        String link = baseUrl + "/einladung/" + token;
        boolean sent = true;
        try {
            mail.send(email, "Einladung zur Gruppenkasse " + g.name,
                    "Du wurdest als Kassenverwalter der Wohngruppe \"" + g.name + "\" eingeladen.\n\n"
                            + "Über diesen Link kannst du die Einladung annehmen (7 Tage gültig):\n" + link);
        } catch (RuntimeException ex) {
            sent = false;
        }
        return new InviteResponse(sent, link);
    }

    @Transactional(readOnly = true)
    public InvitationInfo info(String token) {
        Einladung e = valid(token);
        String name = gruppen.findById(e.wohngruppeId).orElseThrow().name;
        return new InvitationInfo(e.email, name, people.findByEmail(e.email).isPresent());
    }

    /** Löst die Einladung ein. Gibt die Person zurück, die danach angemeldet sein soll. */
    @Transactional
    public Person redeem(String token, RedeemRequest r) {
        Einladung e = valid(token);
        Person p = people.findByEmail(e.email).orElse(null);
        if (p == null) {
            if (r.name() == null || r.name().isBlank()) {
                throw new IllegalArgumentException("Name fehlt");
            }
            if (r.password() == null || r.password().length() < 10) {
                throw new IllegalArgumentException("Passwort braucht mindestens 10 Zeichen");
            }
            if (!Boolean.TRUE.equals(r.datenschutzZugestimmt())) {
                throw new IllegalArgumentException("Der Datenschutzhinweis muss bestätigt werden");
            }
            p = new Person();
            p.name = r.name().trim();
            p.email = e.email;
            p.passwortHash = encoder.encode(r.password());
            p.datenschutzZugestimmtAm = Instant.now();
            p = people.save(p);
        } else {
            if (user.principal().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bitte zuerst mit dem bestehenden Konto anmelden");
            }
            if (!user.personId().equals(p.id)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Die Einladung gehört zu einem anderen Konto");
            }
        }
        Mitgliedschaft m = members.findByPersonIdAndWohngruppeId(p.id, e.wohngruppeId).orElseGet(Mitgliedschaft::new);
        m.personId = p.id;
        m.wohngruppeId = e.wohngruppeId;
        m.rolle = Rolle.KASSENVERWALTER;
        m.status = MitgliedStatus.AKTIV;
        m.freigeschaltetAm = Instant.now();
        members.save(m);
        e.eingeloestAm = Instant.now();
        return p;
    }

    private Einladung valid(String token) {
        return einladungen.findByToken(TokenUtil.hash(token))
                .filter(x -> x.eingeloestAm == null && x.gueltigBis.isAfter(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Einladung ungültig oder abgelaufen"));
    }
}

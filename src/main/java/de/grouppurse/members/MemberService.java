package de.grouppurse.members;

import de.grouppurse.audit.AuditService;
import de.grouppurse.auth.PasswordResetService;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.members.MemberDtos.MemberDto;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MemberService {
    private final MitgliedschaftRepository members;
    private final PersonRepository people;
    private final CurrentUser user;
    private final PasswordResetService resets;
    private final AuditService audit;

    public MemberService(MitgliedschaftRepository members, PersonRepository people, CurrentUser user,
                         PasswordResetService resets, AuditService audit) {
        this.members = members;
        this.people = people;
        this.user = user;
        this.resets = resets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberDto> list(UUID wohngruppeId) {
        List<Mitgliedschaft> ms = members.findByWohngruppeId(wohngruppeId);
        Map<UUID, Person> persons = people.findAllById(ms.stream().map(m -> m.personId).toList()).stream()
                .collect(Collectors.toMap(p -> p.id, p -> p));
        return ms.stream()
                .map(m -> new MemberDto(m.id, m.personId, persons.get(m.personId).name, persons.get(m.personId).email, m.rolle, m.status))
                .sorted(Comparator.comparing(MemberDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public MemberDto activate(UUID wohngruppeId, UUID mitgliedschaftId) {
        Mitgliedschaft m = target(wohngruppeId, mitgliedschaftId);
        m.status = MitgliedStatus.AKTIV;
        m.freigeschaltetVon = user.personId();
        m.freigeschaltetAm = Instant.now();
        return dto(m);
    }

    @Transactional
    public MemberDto block(UUID wohngruppeId, UUID mitgliedschaftId) {
        Mitgliedschaft m = target(wohngruppeId, mitgliedschaftId);
        if (m.personId.equals(user.personId())) {
            throw new IllegalArgumentException("Du kannst dich nicht selbst sperren");
        }
        m.status = MitgliedStatus.GESPERRT;
        return dto(m);
    }

    /** Einmaliger Link zum Setzen eines neuen Passworts; Kassenverwalter dürfen das nur für Pflegekräfte. */
    @Transactional
    public String passwordLink(UUID wohngruppeId, UUID mitgliedschaftId) {
        Mitgliedschaft m = target(wohngruppeId, mitgliedschaftId);
        String link = resets.createLink(m.personId);
        audit.log("Passwort-Link erzeugt", "Person", m.personId, null, null);
        return link;
    }

    private Mitgliedschaft target(UUID wohngruppeId, UUID id) {
        Mitgliedschaft m = members.findById(id)
                .filter(x -> x.wohngruppeId.equals(wohngruppeId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mitglied nicht gefunden"));
        if (m.rolle == Rolle.KASSENVERWALTER && !user.istAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kassenverwalter verwaltet nur der Administrator");
        }
        return m;
    }

    private MemberDto dto(Mitgliedschaft m) {
        Person p = people.findById(m.personId).orElseThrow();
        return new MemberDto(m.id, p.id, p.name, p.email, m.rolle, m.status);
    }
}

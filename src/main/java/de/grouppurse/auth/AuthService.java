package de.grouppurse.auth;

import de.grouppurse.auth.AuthDtos.MeDto;
import de.grouppurse.auth.AuthDtos.MitgliedschaftDto;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.WohngruppeRepository;
import de.grouppurse.security.AppPrincipal;
import de.grouppurse.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final MitgliedschaftRepository members;
    private final WohngruppeRepository gruppen;
    private final CurrentUser user;
    private final SecurityContextRepository contextRepository;

    public AuthService(MitgliedschaftRepository members, WohngruppeRepository gruppen, CurrentUser user,
                       SecurityContextRepository contextRepository) {
        this.members = members;
        this.gruppen = gruppen;
        this.user = user;
        this.contextRepository = contextRepository;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static AppPrincipal principalOf(Person p) {
        return new AppPrincipal(p.id, p.name, p.email, p.istAdmin);
    }

    /** Legt die Session an (neue Session-ID gegen Session-Fixation) und setzt ggf. die Standardgruppe. */
    public void startSession(Person p, HttpServletRequest req, HttpServletResponse res) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        String[] roles = p.istAdmin ? new String[]{"ROLE_USER", "ROLE_ADMIN"} : new String[]{"ROLE_USER"};
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principalOf(p), null,
                AuthorityUtils.createAuthorityList(roles)));
        SecurityContextHolder.setContext(ctx);
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        HttpSession session = req.getSession(true);
        session.removeAttribute(CurrentUser.ACTIVE_GROUP);
        defaultGroup(p).ifPresent(g -> session.setAttribute(CurrentUser.ACTIVE_GROUP, g));
        contextRepository.saveContext(ctx, req, res);
    }

    /** Gruppe, die nach dem Login automatisch aktiv wird: genau eine aktive Mitgliedschaft. */
    @Transactional(readOnly = true)
    public Optional<UUID> defaultGroup(Person p) {
        List<Mitgliedschaft> active = members.findByPersonId(p.id).stream()
                .filter(m -> m.status == MitgliedStatus.AKTIV).toList();
        return active.size() == 1 ? Optional.of(active.get(0).wohngruppeId) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean mayActivate(UUID wohngruppeId) {
        if (user.istAdmin()) {
            return gruppen.existsById(wohngruppeId);
        }
        return members.findByPersonIdAndWohngruppeId(user.personId(), wohngruppeId)
                .filter(m -> m.status == MitgliedStatus.AKTIV).isPresent();
    }

    @Transactional(readOnly = true)
    public MeDto me() {
        AppPrincipal p = user.principal().orElseThrow();
        List<Mitgliedschaft> ms = members.findByPersonId(p.personId());
        Map<UUID, String> names = gruppen.findAllById(ms.stream().map(m -> m.wohngruppeId).toList()).stream()
                .collect(Collectors.toMap(g -> g.id, g -> g.name));
        List<AuthDtos.MitgliedschaftDto> dtos = ms.stream()
                .map(m -> new MitgliedschaftDto(m.id, m.wohngruppeId, names.get(m.wohngruppeId), m.rolle, m.status))
                .toList();
        return new MeDto(p.personId(), p.name(), p.email(), p.istAdmin(), dtos,
                user.activeGroup().orElse(null), user.activeRole().orElse(null));
    }
}

package de.grouppurse.security;

import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Rolle;
import de.grouppurse.repo.MitgliedschaftRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUser {
    public static final String ACTIVE_GROUP = "activeGroupId";

    private final MitgliedschaftRepository members;

    public CurrentUser(MitgliedschaftRepository members) {
        this.members = members;
    }

    public Optional<AppPrincipal> principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public UUID personId() {
        return principal().orElseThrow(() -> new IllegalStateException("Nicht angemeldet")).personId();
    }

    public boolean istAdmin() {
        return principal().map(AppPrincipal::istAdmin).orElse(false);
    }

    public Optional<UUID> activeGroup() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            HttpSession s = sra.getRequest().getSession(false);
            if (s != null && s.getAttribute(ACTIVE_GROUP) instanceof UUID id) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /** Wirksame Rolle in der aktiven Gruppe; Admin zählt als Kassenverwalter. Leer, wenn kein aktives Mitglied. */
    public Optional<Rolle> activeRole() {
        Optional<UUID> g = activeGroup();
        if (g.isEmpty() || principal().isEmpty()) {
            return Optional.empty();
        }
        return roleIn(g.get());
    }

    public Optional<Rolle> roleIn(UUID wohngruppeId) {
        if (istAdmin()) {
            return Optional.of(Rolle.KASSENVERWALTER);
        }
        return members.findByPersonIdAndWohngruppeId(personId(), wohngruppeId)
                .filter(m -> m.status == MitgliedStatus.AKTIV)
                .map(m -> m.rolle);
    }

    public Optional<Mitgliedschaft> membership(UUID wohngruppeId) {
        return members.findByPersonIdAndWohngruppeId(personId(), wohngruppeId);
    }
}

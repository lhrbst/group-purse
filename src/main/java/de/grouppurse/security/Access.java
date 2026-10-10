package de.grouppurse.security;

import de.grouppurse.domain.Rolle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class Access {
    private final CurrentUser user;

    public Access(CurrentUser user) {
        this.user = user;
    }

    public UUID requireGroup() {
        return user.activeGroup().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keine aktive Wohngruppe gewählt"));
    }

    public void requireAdmin() {
        if (!user.istAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für Administratoren");
        }
    }

    /** Aktives Mitglied der aktiven Gruppe (oder Admin). Liefert die wirksame Rolle. */
    public Rolle requireActiveMember() {
        requireGroup();
        return user.activeRole().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Wohngruppe"));
    }

    public void requireKassenverwalter() {
        if (requireActiveMember() != Rolle.KASSENVERWALTER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für Kassenverwalter");
        }
    }
}

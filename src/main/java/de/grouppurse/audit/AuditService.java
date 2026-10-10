package de.grouppurse.audit;

import de.grouppurse.domain.AuditLog;
import de.grouppurse.repo.AuditLogRepository;
import de.grouppurse.security.CurrentUser;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** Schreibt jede Änderung ins Audit-Log der aktiven Wohngruppe (läuft in der Transaktion des Aufrufers mit). */
@Service
public class AuditService {
    private final AuditLogRepository repo;
    private final CurrentUser user;
    private final JsonMapper json;

    public AuditService(AuditLogRepository repo, CurrentUser user, JsonMapper json) {
        this.repo = repo;
        this.user = user;
        this.json = json;
    }

    public void log(String was, String entitaet, UUID entitaetId, Object alt, Object neu) {
        AuditLog a = new AuditLog();
        a.wer = user.principal().map(p -> p.personId()).orElse(null);
        a.was = was;
        a.entitaet = entitaet;
        a.entitaetId = entitaetId;
        a.alt = serialize(alt);
        a.neu = serialize(neu);
        repo.save(a);
    }

    private String serialize(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return json.writeValueAsString(o);
        } catch (RuntimeException e) {
            return String.valueOf(o);
        }
    }
}

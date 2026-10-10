package de.grouppurse;

import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.MitgliedschaftRepository;
import de.grouppurse.repo.PersonRepository;
import de.grouppurse.repo.WohngruppeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Testdaten und Login-Helfer. Passwort aller Testpersonen: PASSWORD. */
@Component
public class TestSupport {
    public static final String PASSWORD = "passwort1234";

    @Autowired PersonRepository people;
    @Autowired WohngruppeRepository gruppen;
    @Autowired MitgliedschaftRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    }

    public Person person(String prefix, boolean admin) {
        Person p = new Person();
        p.name = prefix;
        p.email = uniqueEmail(prefix);
        p.passwortHash = encoder.encode(PASSWORD);
        p.istAdmin = admin;
        return people.save(p);
    }

    public Wohngruppe group(String name) {
        Wohngruppe g = new Wohngruppe();
        g.name = name;
        g.beitrittscode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return gruppen.save(g);
    }

    public Mitgliedschaft member(Person p, Wohngruppe g, Rolle rolle, MitgliedStatus status) {
        Mitgliedschaft m = new Mitgliedschaft();
        m.personId = p.id;
        m.wohngruppeId = g.id;
        m.rolle = rolle;
        m.status = status;
        return members.save(m);
    }

    public MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession();
    }

    /** Einloggen und Gruppe aktiv setzen. */
    public MockHttpSession sessionFor(Person p, Wohngruppe g) throws Exception {
        MockHttpSession s = login(p.email);
        mvc.perform(post("/api/me/active-group").with(csrf()).session(s)
                        .contentType("application/json").content("{\"wohngruppeId\":\"" + g.id + "\"}"))
                .andExpect(status().isOk());
        return s;
    }

    public MockHttpSession kassenverwalter(Wohngruppe g) throws Exception {
        Person p = person("kv", false);
        member(p, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        return sessionFor(p, g);
    }

    public MockHttpSession pflegekraft(Wohngruppe g) throws Exception {
        Person p = person("pk", false);
        member(p, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        return sessionFor(p, g);
    }
}

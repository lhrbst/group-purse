package de.grouppurse.members;

import com.jayway.jsonpath.JsonPath;
import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Passwort vergessen ohne E-Mail: Der Kassenverwalter erzeugt einen einmaligen Link und gibt ihn weiter. */
class PasswordLinkTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";

    private String linkFor(MockHttpSession session, Mitgliedschaft m) throws Exception {
        String body = mvc.perform(post("/api/members/" + m.id + "/passwort-link").with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.link");
    }

    @Test
    void managerCreatesLinkAndCaregiverSetsNewPassword() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        Person pk = t.person("pk", false);
        Mitgliedschaft m = t.member(pk, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);

        String link = linkFor(kv, m);
        assertThat(link).contains("/passwort-neu/");
        String token = link.substring(link.lastIndexOf('/') + 1);

        String confirm = "{\"token\":\"" + token + "\",\"newPassword\":\"ganz-neues-passwort-7\"}";
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(JSON).content(confirm)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(JSON).content(confirm)).andExpect(status().isBadRequest());
        assertThat(t.login(pk.email, "ganz-neues-passwort-7")).isNotNull();
    }

    @Test
    void shortPasswordAndUnknownTokenAreRejected() throws Exception {
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(JSON)
                .content("{\"token\":\"gibt-es-nicht\",\"newPassword\":\"ganz-neues-passwort-7\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType(JSON)
                .content("{\"token\":\"x\",\"newPassword\":\"kurz\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void oldRequestByEmailEndpointIsGone() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType(JSON).content("{\"email\":\"a@b.de\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void managerCannotResetAnotherManagerButAdminCan() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        Person other = t.person("kv2", false);
        Mitgliedschaft m = t.member(other, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        mvc.perform(post("/api/members/" + m.id + "/passwort-link").with(csrf()).session(kv)).andExpect(status().isForbidden());

        Person admin = t.person("admin", true);
        MockHttpSession adminSession = t.sessionFor(admin, g);
        assertThat(linkFor(adminSession, m)).contains("/passwort-neu/");
    }

    @Test
    void caregiversAndForeignManagersGetNothing() throws Exception {
        Wohngruppe g = t.group("G");
        Person target = t.person("target", false);
        Mitgliedschaft m = t.member(target, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(post("/api/members/" + m.id + "/passwort-link").with(csrf()).session(pk)).andExpect(status().isForbidden());
        MockHttpSession foreignKv = t.kassenverwalter(t.group("Fremd"));
        mvc.perform(post("/api/members/" + m.id + "/passwort-link").with(csrf()).session(foreignKv)).andExpect(status().isNotFound());
    }
}

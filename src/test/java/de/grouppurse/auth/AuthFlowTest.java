package de.grouppurse.auth;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.TestSupport;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends AbstractIntegrationTest {

    private String loginJson(String email, String pw) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}";
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        Person p = t.person("a", false);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginJson(p.email, "falsch-falsch")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenMeWorks() throws Exception {
        Person p = t.person("anna", false);
        MockHttpSession s = t.login(p.email);
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("anna"))
                .andExpect(jsonPath("$.istAdmin").value(false));
    }

    @Test
    void meWithoutLoginIs401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrfTokenIs403() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content(loginJson("x@example.org", "y")))
                .andExpect(status().isForbidden());
    }

    @Test
    void singleActiveMembershipBecomesActiveGroupAutomatically() throws Exception {
        Wohngruppe g = t.group("G");
        Person p = t.person("pk", false);
        t.member(p, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        MockHttpSession s = t.login(p.email);
        mvc.perform(get("/api/me").session(s))
                .andExpect(jsonPath("$.activeGroupId").value(g.id.toString()))
                .andExpect(jsonPath("$.activeRole").value("PFLEGEKRAFT"));
    }

    @Test
    void cannotActivateForeignGroup() throws Exception {
        Wohngruppe own = t.group("Own");
        Wohngruppe foreign = t.group("Foreign");
        Person p = t.person("pk", false);
        t.member(p, own, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        MockHttpSession s = t.login(p.email);
        mvc.perform(post("/api/me/active-group").with(csrf()).session(s).contentType("application/json")
                        .content("{\"wohngruppeId\":\"" + foreign.id + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void waitingMemberCannotActivateGroup() throws Exception {
        Wohngruppe g = t.group("G");
        Person p = t.person("wait", false);
        t.member(p, g, Rolle.PFLEGEKRAFT, MitgliedStatus.WARTEND);
        MockHttpSession s = t.login(p.email);
        mvc.perform(post("/api/me/active-group").with(csrf()).session(s).contentType("application/json")
                        .content("{\"wohngruppeId\":\"" + g.id + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanActivateAnyGroup() throws Exception {
        Wohngruppe g = t.group("Any");
        Person admin = t.person("admin", true);
        MockHttpSession s = t.login(admin.email);
        mvc.perform(post("/api/me/active-group").with(csrf()).session(s).contentType("application/json")
                        .content("{\"wohngruppeId\":\"" + g.id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeGroupId").value(g.id.toString()))
                .andExpect(jsonPath("$.activeRole").value("KASSENVERWALTER"));
    }

    @Test
    void logoutEndsSession() throws Exception {
        Person p = t.person("out", false);
        MockHttpSession s = t.login(p.email);
        mvc.perform(post("/api/auth/logout").with(csrf()).session(s)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetFlow() throws Exception {
        Person p = t.person("reset", false);
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + p.email + "\"}")).andExpect(status().isNoContent());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(p.email), anyString(), text.capture());
        Matcher m = Pattern.compile("/passwort-neu/(\\S+)").matcher(text.getValue());
        assertThat(m.find()).isTrue();
        String token = m.group(1);

        String confirm = "{\"token\":\"" + token + "\",\"newPassword\":\"neues-passwort-99\"}";
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType("application/json").content(confirm))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/password-reset/confirm").with(csrf()).contentType("application/json").content(confirm))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginJson(p.email, "neues-passwort-99")))
                .andExpect(status().isOk());
    }

    @Test
    void resetForUnknownEmailStill204() throws Exception {
        mvc.perform(post("/api/auth/password-reset/request").with(csrf()).contentType("application/json")
                .content("{\"email\":\"niemand@example.org\"}")).andExpect(status().isNoContent());
    }

    @Test
    void tooManyFailuresBlockLogin() throws Exception {
        Person p = t.person("brute", false);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginJson(p.email, "falsch-falsch")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginJson(p.email, TestSupport.PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }
}

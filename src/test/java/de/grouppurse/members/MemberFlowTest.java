package de.grouppurse.members;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.TestSupport;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberFlowTest extends AbstractIntegrationTest {

    private String registerJson(String email, String code, boolean consent) {
        return "{\"name\":\"Pflege Paula\",\"email\":\"" + email + "\",\"password\":\"passwort-lang-1\",\"beitrittscode\":\""
                + code + "\",\"datenschutzZugestimmt\":" + consent + "}";
    }

    @Test
    void registrationWithWrongCodeIs400() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                .content(registerJson(TestSupport.uniqueEmail("p"), "ZZZZZZZZ", true))).andExpect(status().isBadRequest());
    }

    @Test
    void registrationWithoutConsentIs400() throws Exception {
        Wohngruppe g = t.group("G");
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                .content(registerJson(TestSupport.uniqueEmail("p"), g.beitrittscode, false))).andExpect(status().isBadRequest());
    }

    @Test
    void waitingCaregiverCanDoNothingUntilApproved() throws Exception {
        Wohngruppe g = t.group("G");
        String email = TestSupport.uniqueEmail("wait");
        MockHttpSession pk = (MockHttpSession) mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content(registerJson(email, g.beitrittscode.toLowerCase(), true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mitgliedschaften[0].status").value("WARTEND"))
                .andReturn().getRequest().getSession();

        mvc.perform(get("/api/group").session(pk)).andExpect(status().isBadRequest()); // keine aktive Gruppe wählbar

        MockHttpSession kv = t.kassenverwalter(g);
        String body = mvc.perform(get("/api/members").session(kv)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String memberId = body.replaceAll(".*\"mitgliedschaftId\":\"([^\"]+)\"[^}]*\"email\":\"" + email.replace(".", "\\.") + "\".*", "$1");
        mvc.perform(post("/api/members/" + memberId + "/freischalten").with(csrf()).session(kv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AKTIV"));

        MockHttpSession again = t.login(email, "passwort-lang-1");
        mvc.perform(get("/api/group").session(again)).andExpect(status().isOk())
                .andExpect(jsonPath("$.beitrittscode").doesNotExist());
    }

    @Test
    void caregiverCannotManageMembersOrSettings() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/members").session(pk)).andExpect(status().isForbidden());
        mvc.perform(put("/api/group/settings").with(csrf()).session(pk).contentType("application/json")
                .content("{\"mindestbetragKasse\":10}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/group/beitrittscode/neu").with(csrf()).session(pk)).andExpect(status().isForbidden());
    }

    @Test
    void blockedCaregiverLosesAccessButStaysListed() throws Exception {
        Wohngruppe g = t.group("G");
        Person p = t.person("gesperrt", false);
        var m = t.member(p, g, Rolle.PFLEGEKRAFT, MitgliedStatus.AKTIV);
        MockHttpSession pk = t.sessionFor(p, g);
        MockHttpSession kv = t.kassenverwalter(g);

        mvc.perform(post("/api/members/" + m.id + "/sperren").with(csrf()).session(kv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GESPERRT"));

        mvc.perform(get("/api/group").session(pk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/members").session(kv)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='gesperrt')].status").value("GESPERRT"));
    }

    @Test
    void kassenverwalterCannotBlockAnotherKassenverwalter() throws Exception {
        Wohngruppe g = t.group("G");
        Person other = t.person("kv2", false);
        var m = t.member(other, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(post("/api/members/" + m.id + "/sperren").with(csrf()).session(kv)).andExpect(status().isForbidden());
    }

    @Test
    void newBeitrittscodeInvalidatesOldOne() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        String old = g.beitrittscode;
        mvc.perform(post("/api/group/beitrittscode/neu").with(csrf()).session(kv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beitrittscode").value(org.hamcrest.Matchers.not(old)));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                .content(registerJson(TestSupport.uniqueEmail("p"), old, true))).andExpect(status().isBadRequest());
    }

    @Test
    void kassenverwalterChangesMinimumBalance() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(put("/api/group/settings").with(csrf()).session(kv).contentType("application/json")
                        .content("{\"mindestbetragKasse\":75.50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mindestbetragKasse").value(75.50));
    }
}

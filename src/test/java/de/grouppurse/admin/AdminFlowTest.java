package de.grouppurse.admin;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.TestSupport;
import de.grouppurse.auth.TokenUtil;
import de.grouppurse.domain.Einladung;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.EinladungRepository;
import de.grouppurse.repo.MitgliedschaftRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminFlowTest extends AbstractIntegrationTest {
    @Autowired EinladungRepository einladungen;
    @Autowired MitgliedschaftRepository members;

    private String inviteLink(MockHttpSession admin, Wohngruppe g, String email) throws Exception {
        String body = mvc.perform(post("/api/admin/wohngruppen/" + g.id + "/kassenverwalter-einladung").with(csrf()).session(admin)
                        .contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String link = body.replaceAll(".*\"link\":\"([^\"]+)\".*", "$1");
        return link.substring(link.lastIndexOf('/') + 1);
    }

    @Test
    void nonAdminIsForbiddenEverywhere() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(post("/api/admin/wohngruppen").with(csrf()).session(kv).contentType("application/json").content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/wohngruppen").session(kv)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/wohngruppen/" + g.id + "/kassenverwalter-einladung").with(csrf()).session(kv)
                .contentType("application/json").content("{\"email\":\"a@b.de\"}")).andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesGroupWithEightCharCode() throws Exception {
        MockHttpSession admin = t.login(t.person("admin", true).email);
        mvc.perform(post("/api/admin/wohngruppen").with(csrf()).session(admin).contentType("application/json")
                        .content("{\"name\":\"WG Sonnenhof\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("WG Sonnenhof"))
                .andExpect(jsonPath("$.beitrittscode").value(org.hamcrest.Matchers.matchesPattern("[A-Z2-9]{8}")));
        mvc.perform(get("/api/admin/wohngruppen").session(admin)).andExpect(status().isOk());
    }

    @Test
    void invitationCreatesNewKassenverwalterAccount() throws Exception {
        Wohngruppe g = t.group("Einladung");
        MockHttpSession admin = t.login(t.person("admin", true).email);
        String email = TestSupport.uniqueEmail("neu");
        String token = inviteLink(admin, g, email);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(email), anyString(), text.capture());
        assertThat(text.getValue()).contains("/einladung/" + token);

        mvc.perform(get("/api/einladungen/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.kontoExistiert").value(false));

        mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).contentType("application/json")
                        .content("{\"name\":\"Neue Kasse\",\"password\":\"passwort-lang-1\",\"datenschutzZugestimmt\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeGroupId").value(g.id.toString()))
                .andExpect(jsonPath("$.activeRole").value("KASSENVERWALTER"));

        mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).contentType("application/json")
                        .content("{\"name\":\"Nochmal\",\"password\":\"passwort-lang-1\",\"datenschutzZugestimmt\":true}"))
                .andExpect(status().isGone());
    }

    @Test
    void existingAccountMustBeLoggedInToRedeem() throws Exception {
        Wohngruppe g = t.group("Bestehend");
        Person kv = t.person("kvbestand", false);
        MockHttpSession admin = t.login(t.person("admin", true).email);
        String token = inviteLink(admin, g, kv.email);

        mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        Person other = t.person("fremd", false);
        mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).session(t.login(other.email))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());

        mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).session(t.login(kv.email))
                .contentType("application/json").content("{}")).andExpect(status().isOk());
        assertThat(members.findByPersonIdAndWohngruppeId(kv.id, g.id)).get()
                .satisfies(m -> {
                    assertThat(m.rolle).isEqualTo(Rolle.KASSENVERWALTER);
                    assertThat(m.status).isEqualTo(MitgliedStatus.AKTIV);
                });
    }

    @Test
    void expiredInvitationIsGone() throws Exception {
        Wohngruppe g = t.group("Abgelaufen");
        Einladung e = new Einladung();
        e.token = TokenUtil.hash("abgelaufen-token");
        e.email = TestSupport.uniqueEmail("x");
        e.wohngruppeId = g.id;
        e.rolle = Rolle.KASSENVERWALTER;
        e.gueltigBis = Instant.now().minus(1, ChronoUnit.HOURS);
        einladungen.save(e);
        mvc.perform(get("/api/einladungen/abgelaufen-token")).andExpect(status().isGone());
    }
}

package de.grouppurse.contributions;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContributionControllerTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";

    @Test
    void caregiverHasNoAccess() throws Exception {
        MockHttpSession pk = t.pflegekraft(t.group("G"));
        mvc.perform(get("/api/families").session(pk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/contributions/grid").session(pk)).andExpect(status().isForbidden());
        mvc.perform(post("/api/contributions").with(csrf()).session(pk).contentType(JSON)
                .content("{\"familieId\":\"" + java.util.UUID.randomUUID() + "\",\"betrag\":10,\"monate\":[\"2026-01\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void contributionRaisesBankBalanceAndAppearsInGrid() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        String month = java.time.YearMonth.now().toString();
        String first = java.time.YearMonth.now().atDay(1).toString();

        String fam = mvc.perform(post("/api/families").with(csrf()).session(kv).contentType(JSON)
                        .content("{\"name\":\"Meier\",\"bewohnerKuerzel\":\"T.\",\"faelligkeitstag\":3,\"sollbetrag\":50,\"gueltigAb\":\"" + first + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.aktuellerSollbetrag").value(50))
                .andReturn().getResponse().getContentAsString();
        String id = fam.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/contributions").with(csrf()).session(kv).contentType(JSON)
                        .content("{\"familieId\":\"" + id + "\",\"betrag\":50,\"eingangsdatum\":\"" + LocalDate.now() + "\",\"monate\":[\"" + month + "\"],\"notiz\":\"Überweisung\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.vereinskonto").value(50));
        mvc.perform(get("/api/contributions/grid?von=" + month + "&bis=" + month).session(kv))
                .andExpect(jsonPath("$.zeilen[0].name").value("Meier"))
                .andExpect(jsonPath("$.zeilen[0].cells[0].status").value("BEZAHLT"));
        mvc.perform(get("/api/contributions/summary").session(kv)).andExpect(jsonPath("$.ueberfaellig").value(0));
    }

    @Test
    void validationErrors() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        String fam = mvc.perform(post("/api/families").with(csrf()).session(kv).contentType(JSON)
                .content("{\"name\":\"Meier\",\"faelligkeitstag\":3}")).andReturn().getResponse().getContentAsString();
        String id = fam.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/contributions").with(csrf()).session(kv).contentType(JSON)
                .content("{\"familieId\":\"" + id + "\",\"betrag\":50,\"monate\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/contributions").with(csrf()).session(kv).contentType(JSON)
                .content("{\"familieId\":\"" + id + "\",\"betrag\":50,\"monate\":[\"Januar\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/contributions").with(csrf()).session(kv).contentType(JSON)
                .content("{\"familieId\":\"" + id + "\",\"betrag\":-5,\"monate\":[\"2026-01\"]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/families/" + id).with(csrf()).session(kv).contentType(JSON)
                .content("{\"name\":\"Meier\",\"faelligkeitstag\":31}")).andExpect(status().isBadRequest());
    }

    @Test
    void familiesOfOtherGroupsAreInvisible() throws Exception {
        MockHttpSession a = t.kassenverwalter(t.group("A"));
        MockHttpSession b = t.kassenverwalter(t.group("B"));
        String fam = mvc.perform(post("/api/families").with(csrf()).session(a).contentType(JSON)
                .content("{\"name\":\"Nur A\"}")).andReturn().getResponse().getContentAsString();
        String id = fam.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/families").session(b)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(put("/api/families/" + id).with(csrf()).session(b).contentType(JSON).content("{\"name\":\"Hack\"}"))
                .andExpect(status().isNotFound());
    }
}

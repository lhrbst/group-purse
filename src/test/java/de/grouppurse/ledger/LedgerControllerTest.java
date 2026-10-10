package de.grouppurse.ledger;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerControllerTest extends AbstractIntegrationTest {

    @Test
    void caregiverCannotWriteLedger() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        String json = "application/json";
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(pk).contentType(json)
                .content("{\"konto\":\"BARKASSE\",\"betrag\":10}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ledger/auffuellung").with(csrf()).session(pk).contentType(json)
                .content("{\"betrag\":10,\"uebergebenAn\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ledger/rueckgabe").with(csrf()).session(pk).contentType(json)
                .content("{\"betrag\":10,\"grund\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ledger/korrektur").with(csrf()).session(pk).contentType(json)
                .content("{\"konto\":\"BARKASSE\",\"betrag\":10,\"begruendung\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ledger/bestand").with(csrf()).session(pk).contentType(json)
                .content("{\"konto\":\"BARKASSE\",\"istBestand\":10}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ledger").session(pk)).andExpect(status().isForbidden());
    }

    @Test
    void caregiverSeesCashBalanceButNotBankAccount() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType("application/json")
                .content("{\"konto\":\"BARKASSE\",\"betrag\":120.50}")).andExpect(status().isOk());
        mvc.perform(get("/api/balance").session(pk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.barkasse").value(120.5))
                .andExpect(jsonPath("$.vereinskonto").doesNotExist());
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.vereinskonto").value(0));
    }

    @Test
    void managerWorkflowOverHttp() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        String json = "application/json";
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(json)
                .content("{\"konto\":\"VEREINSKONTO\",\"betrag\":1000}")).andExpect(status().isOk());
        mvc.perform(post("/api/ledger/auffuellung").with(csrf()).session(kv).contentType(json)
                .content("{\"betrag\":40,\"uebergebenAn\":\"Kasse direkt\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/balance").session(kv))
                .andExpect(jsonPath("$.barkasse").value(40))
                .andExpect(jsonPath("$.vereinskonto").value(960))
                .andExpect(jsonPath("$.warnung").value(true))   // Standard-Mindestbetrag 50
                .andExpect(jsonPath("$.negativ").value(false));
        mvc.perform(post("/api/ledger/bestand").with(csrf()).session(kv).contentType(json)
                        .content("{\"konto\":\"BARKASSE\",\"istBestand\":35,\"begruendung\":\"Kaffee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenz").value(-5));
        mvc.perform(post("/api/ledger/bestand").with(csrf()).session(kv).contentType(json)
                .content("{\"konto\":\"BARKASSE\",\"istBestand\":20}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ledger").session(kv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }
}

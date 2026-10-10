package de.grouppurse.notify;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Person;
import de.grouppurse.domain.Rolle;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LowBalanceMonitorTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";
    @MockitoBean PushService push;

    Wohngruppe g;
    Person kv1;
    Person kv2;
    MockHttpSession kvSession;
    MockHttpSession pk;
    String category;

    @BeforeEach
    void setUp() throws Exception {
        g = t.group("Warnung");
        kv1 = t.person("kv1", false);
        kv2 = t.person("kv2", false);
        t.member(kv1, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        t.member(kv2, g, Rolle.KASSENVERWALTER, MitgliedStatus.AKTIV);
        kvSession = t.sessionFor(kv1, g);
        pk = t.pflegekraft(g);
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kvSession).contentType(JSON)
                .content("{\"konto\":\"BARKASSE\",\"betrag\":60}")).andExpect(status().isOk());
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kvSession).contentType(JSON)
                .content("{\"konto\":\"VEREINSKONTO\",\"betrag\":1000}")).andExpect(status().isOk());
        String cats = mvc.perform(get("/api/categories").session(kvSession)).andReturn().getResponse().getContentAsString();
        category = cats.replaceAll(".*\\{\"id\":\"([^\"]+)\",\"name\":\"Lebensmittel\".*", "$1");
    }

    void spend(String amount) throws Exception {
        String data = "{\"betrag\":" + amount + ",\"datum\":\"" + LocalDate.now() + "\",\"kategorieId\":\"" + category + "\"}";
        mvc.perform(multipart("/api/expenses")
                        .file(new MockMultipartFile("data", "", JSON, data.getBytes()))
                        .file(new MockMultipartFile("photos", "b.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0}))
                        .with(csrf()).session(pk))
                .andExpect(status().isCreated());
    }

    @Test
    void warnsEveryManagerOnceAndAgainOnlyAfterRefill() throws Exception {
        spend("5.00"); // 55, noch über dem Mindestbetrag 50
        verify(mailService, never()).send(eq(kv1.email), anyString(), anyString());

        spend("10.00"); // 45, unter 50
        verify(mailService, times(1)).send(eq(kv1.email), anyString(), anyString());
        verify(mailService, times(1)).send(eq(kv2.email), anyString(), anyString());
        verify(push, times(1)).sendTo(eq(kv1.id), anyString(), anyString());

        spend("10.00"); // 35, Warnung läuft schon
        verify(mailService, times(1)).send(eq(kv1.email), anyString(), anyString());

        mvc.perform(post("/api/ledger/auffuellung").with(csrf()).session(kvSession).contentType(JSON)
                .content("{\"betrag\":100,\"uebergebenAn\":\"Kasse direkt\"}")).andExpect(status().isOk());
        spend("100.00"); // 25, wieder unter 50
        verify(mailService, times(2)).send(eq(kv1.email), anyString(), anyString());
        verify(mailService, times(2)).send(eq(kv2.email), anyString(), anyString());
    }

    @Test
    void caregiversDoNotGetWarningMails() throws Exception {
        spend("20.00");
        verify(mailService, never()).send(org.mockito.ArgumentMatchers.contains("pk-"), anyString(), anyString());
    }
}

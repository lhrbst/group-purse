package de.grouppurse.receipt;

import com.jayway.jsonpath.JsonPath;
import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.receipt.ReceiptModelClient.RawPosition;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Beleg-Hintergrundverarbeitung: Foto hochladen, KI liest im Hintergrund, Ergebnis landet am Beleg. */
@TestPropertySource(properties = "app.receipt.enabled=true")
class ReceiptProcessingTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @MockitoBean ReceiptModelClient client;
    @Autowired ReceiptProcessor processor;
    @Autowired JdbcTemplate jdbc;

    Wohngruppe g;
    MockHttpSession kv;
    MockHttpSession pk;

    @BeforeEach
    void setUp() throws Exception {
        g = t.group("KI-Verarbeitung");
        kv = t.kassenverwalter(g);
        pk = t.pflegekraft(g);
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(JSON)
                .content("{\"konto\":\"BARKASSE\",\"betrag\":100}")).andExpect(status().isOk());
        // Alte Aufträge anderer Testklassen (gleiche Datenbank) sollen die Zählung nicht stören
        jdbc.update("update buchung set status = 'MANUELL' where status = 'VERARBEITUNG' and wohngruppe_id <> ?", g.id);
    }

    String capture(MockHttpSession session) throws Exception {
        String body = mvc.perform(multipart("/api/expenses/capture").file(new MockMultipartFile("photos", "b.jpg", "image/jpeg", JPEG))
                        .with(csrf()).session(session))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    RawReceipt good(String total, String... pairs) {
        var positions = new java.util.ArrayList<RawPosition>();
        for (int i = 0; i < pairs.length; i += 2) {
            positions.add(new RawPosition(pairs[i], new BigDecimal(pairs[i + 1])));
        }
        return new RawReceipt("REWE", LocalDate.now().toString(), new BigDecimal(total), positions);
    }

    void expectCash(double expected) throws Exception {
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.barkasse").value(expected));
    }

    void makeDue(String id) {
        jdbc.update("update buchung set verarbeitung_naechster_versuch = now() - interval '1 minute' where id = ?", UUID.fromString(id));
    }

    @Test
    void captureStartsInProcessingWithoutAmountAndLeavesTheCashUntouched() throws Exception {
        String id = capture(pk);
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERARBEITUNG"))
                .andExpect(jsonPath("$.betrag").value(0.0))
                .andExpect(jsonPath("$.belegAnzahl").value(1))
                .andExpect(jsonPath("$.positionen.length()").value(0));
        expectCash(100);
        mvc.perform(get("/api/dashboard/manager").session(kv)).andExpect(jsonPath("$.ohneBetrag").value(1));
        mvc.perform(get("/api/dashboard/caregiver").session(pk)).andExpect(jsonPath("$.ohneBetrag").value(1));
        mvc.perform(get("/api/expenses/pending").session(kv)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void processorFillsInTheReceiptAndItBecomesReadyForReview() throws Exception {
        String id = capture(pk);
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(good("23.40", "Obst und Gemüse", "8.40", "Haushalt und Drogerie", "15.00")));

        assertThat(processor.runOnce()).isEqualTo(1);

        mvc.perform(get("/api/expenses/" + id).session(kv))
                .andExpect(jsonPath("$.status").value("EINGEREICHT"))
                .andExpect(jsonPath("$.haendler").value("REWE"))
                .andExpect(jsonPath("$.betrag").value(-23.4))
                .andExpect(jsonPath("$.positionen.length()").value(2))
                .andExpect(jsonPath("$.verarbeitungFehler").doesNotExist());
        expectCash(76.6);
        mvc.perform(get("/api/dashboard/manager").session(kv))
                .andExpect(jsonPath("$.ohneBetrag").value(0))
                .andExpect(jsonPath("$.eingereichtAnzahl").value(1));
        assertThat(processor.runOnce()).isZero();
    }

    @Test
    void unreadableReceiptIsRetriedWithBackoffThenHandedToTheManager() throws Exception {
        String id = capture(pk);
        when(client.extract(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(processor.runOnce()).isEqualTo(1);
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(jsonPath("$.status").value("VERARBEITUNG"))
                .andExpect(jsonPath("$.verarbeitungFehler").isNotEmpty());
        assertThat(processor.runOnce()).as("Wartezeit zwischen den Versuchen").isZero();

        makeDue(id);
        assertThat(processor.runOnce()).isEqualTo(1);
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(jsonPath("$.status").value("VERARBEITUNG"));

        makeDue(id);
        assertThat(processor.runOnce()).isEqualTo(1);
        mvc.perform(get("/api/expenses/" + id).session(kv))
                .andExpect(jsonPath("$.status").value("MANUELL"))
                .andExpect(jsonPath("$.verarbeitungFehler").isNotEmpty())
                .andExpect(jsonPath("$.betrag").value(0.0));
        expectCash(100);
        makeDue(id);
        assertThat(processor.runOnce()).isZero();
        mvc.perform(get("/api/dashboard/manager").session(kv)).andExpect(jsonPath("$.ohneBetrag").value(1));
    }

    @Test
    void uncertainReceiptGoesStraightToManualEntryWithTheReasonAndWithoutRetries() throws Exception {
        String id = capture(pk);
        when(client.extract(any(), any(), any()))
                .thenReturn(Optional.of(new RawReceipt(3, null, null, null, null, Boolean.FALSE, "Endbetrag abgeschnitten")));

        assertThat(processor.runOnce()).isEqualTo(1);

        mvc.perform(get("/api/expenses/" + id).session(kv))
                .andExpect(jsonPath("$.status").value("MANUELL"))
                .andExpect(jsonPath("$.verarbeitungFehler").value(org.hamcrest.Matchers.containsString("Endbetrag abgeschnitten")))
                .andExpect(jsonPath("$.betrag").value(0.0));
        expectCash(100);
        makeDue(id);
        assertThat(processor.runOnce()).isZero();
    }

    @Test
    void anImplausibleResultIsNotWrittenToTheReceipt() throws Exception {
        String id = capture(pk);
        // Summe der Positionen (10) passt nicht zur Endsumme (23,40)
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(good("23.40", "Obst und Gemüse", "10.00")));
        assertThat(processor.runOnce()).isEqualTo(1);
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(jsonPath("$.status").value("VERARBEITUNG"))
                .andExpect(jsonPath("$.betrag").value(0.0)).andExpect(jsonPath("$.positionen.length()").value(0));
        expectCash(100);
    }

    @Test
    void managerCanFillInTheReceiptWhileTheAiIsStillWorkingAndTheAiResultThenIsIgnored() throws Exception {
        String id = capture(pk);
        String haushalt = JsonPath.<List<String>>read(mvc.perform(get("/api/categories").session(kv)).andReturn().getResponse().getContentAsString(),
                "$[?(@.name=='Haushalt und Drogerie')].id").get(0);
        mvc.perform(put("/api/expenses/" + id + "/details").with(csrf()).session(kv).contentType(JSON)
                        .content("{\"datum\":\"" + LocalDate.now() + "\",\"positionen\":[{\"kategorieId\":\"" + haushalt + "\",\"betrag\":12.50}],\"haendler\":\"Baumarkt\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EINGEREICHT"));
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(good("99.00", "Obst und Gemüse", "99.00")));

        assertThat(processor.runOnce()).isZero();
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(jsonPath("$.haendler").value("Baumarkt")).andExpect(jsonPath("$.betrag").value(-12.5));
        expectCash(87.5);
    }

    @Test
    void eachGroupsReceiptIsProcessedInItsOwnTenant() throws Exception {
        Wohngruppe other = t.group("Andere KI-WG");
        MockHttpSession otherKv = t.kassenverwalter(other);
        MockHttpSession otherPk = t.pflegekraft(other);
        String mine = capture(pk);
        String theirs = capture(otherPk);
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(good("5.00", "Haushalt und Drogerie", "5.00")));

        assertThat(processor.runOnce()).isEqualTo(2);

        mvc.perform(get("/api/expenses/" + mine).session(kv)).andExpect(jsonPath("$.status").value("EINGEREICHT"));
        mvc.perform(get("/api/expenses/" + theirs).session(otherKv)).andExpect(jsonPath("$.status").value("EINGEREICHT"));
        mvc.perform(get("/api/expenses/" + theirs).session(kv)).andExpect(status().isNotFound());
        mvc.perform(get("/api/expenses/pending").session(otherKv)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void newPhotosAfterRejectionStartProcessingAgain() throws Exception {
        String id = capture(pk);
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(good("23.40", "Obst und Gemüse", "23.40")));
        processor.runOnce();
        mvc.perform(post("/api/expenses/" + id + "/reject").with(csrf()).session(kv).contentType(JSON).content("{\"kommentar\":\"Foto unscharf\"}"))
                .andExpect(status().isOk());
        expectCash(76.6);

        mvc.perform(multipart("/api/expenses/" + id + "/photos").file(new MockMultipartFile("photos", "neu.jpg", "image/jpeg", JPEG))
                        .with(csrf()).with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }).session(pk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERARBEITUNG"))
                .andExpect(jsonPath("$.betrag").value(0.0))
                .andExpect(jsonPath("$.positionen.length()").value(0))
                .andExpect(jsonPath("$.ablehnungsKommentar").doesNotExist());
        expectCash(100);

        assertThat(processor.runOnce()).isEqualTo(1);
        mvc.perform(get("/api/expenses/" + id).session(kv)).andExpect(jsonPath("$.status").value("EINGEREICHT"));
    }
}

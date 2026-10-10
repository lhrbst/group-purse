package de.grouppurse.expense;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExpenseWorkflowTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";

    Wohngruppe g;
    MockHttpSession kv;
    MockHttpSession pk;
    String lebensmittel;

    @BeforeEach
    void setUp() throws Exception {
        g = t.group("Ausgaben");
        kv = t.kassenverwalter(g);
        pk = t.pflegekraft(g);
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(JSON)
                .content("{\"konto\":\"BARKASSE\",\"betrag\":100}")).andExpect(status().isOk());
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(JSON)
                .content("{\"konto\":\"VEREINSKONTO\",\"betrag\":1000}")).andExpect(status().isOk());
        String cats = mvc.perform(get("/api/categories").session(kv)).andReturn().getResponse().getContentAsString();
        lebensmittel = cats.replaceAll(".*\\{\"id\":\"([^\"]+)\",\"name\":\"Lebensmittel\".*", "$1");
    }

    static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    }

    String data(String amount, String date, String extra) {
        return "{\"betrag\":" + amount + ",\"datum\":\"" + date + "\",\"kategorieId\":\"" + lebensmittel + "\"" + extra + "}";
    }

    MockMultipartHttpServletRequestBuilder form(String url, String json, boolean photo) {
        MockMultipartHttpServletRequestBuilder b = multipart(url).file(new MockMultipartFile("data", "", JSON, json.getBytes()));
        if (photo) {
            b.file(new MockMultipartFile("photos", "beleg.jpg", "image/jpeg", jpeg()));
        }
        return b;
    }

    String submit(MockHttpSession s, String amount) throws Exception {
        String body = mvc.perform(form("/api/expenses", data(amount, LocalDate.now().toString(), ",\"haendler\":\"REWE\""), true)
                        .with(csrf()).session(s))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EINGEREICHT"))
                .andExpect(jsonPath("$.betrag").value(-Double.parseDouble(amount)))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    void expectCash(double expected) throws Exception {
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.barkasse").value(expected));
    }

    @Test
    void submitReducesBalanceImmediately() throws Exception {
        submit(pk, "30.00");
        expectCash(70);
    }

    @Test
    void withoutPhotoNeedsReasonAndIsFlagged() throws Exception {
        String d = data("12.00", LocalDate.now().toString(), "");
        mvc.perform(form("/api/expenses", d, false).with(csrf()).session(pk)).andExpect(status().isBadRequest());
        mvc.perform(form("/api/expenses", data("12.00", LocalDate.now().toString(), ",\"begruendungOhneBeleg\":\"Bon verloren\""), false)
                        .with(csrf()).session(pk))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ohneBeleg").value(true));
        mvc.perform(get("/api/expenses/pending").session(kv)).andExpect(jsonPath("$[0].ohneBeleg").value(true));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(form("/api/expenses", data("5.00", LocalDate.now().plusDays(1).toString(), ""), true).with(csrf()).session(pk))
                .andExpect(status().isBadRequest());
        mvc.perform(form("/api/expenses", data("0", LocalDate.now().toString(), ""), true).with(csrf()).session(pk))
                .andExpect(status().isBadRequest());
        mvc.perform(form("/api/expenses", data("5.00", LocalDate.now().toString(), "").replace(lebensmittel, "00000000-0000-0000-0000-000000000001"), true)
                .with(csrf()).session(pk)).andExpect(status().isBadRequest());
        MockMultipartHttpServletRequestBuilder notAnImage = multipart("/api/expenses")
                .file(new MockMultipartFile("data", "", JSON, data("5.00", LocalDate.now().toString(), "").getBytes()))
                .file(new MockMultipartFile("photos", "x.jpg", "image/jpeg", "kein Bild".getBytes()));
        mvc.perform(notAnImage.with(csrf()).session(pk)).andExpect(status().isBadRequest());
        expectCash(100);
    }

    @Test
    void rejectKeepsBalanceAndAllowsResubmitThenApproveLocks() throws Exception {
        String id = submit(pk, "30.00");
        mvc.perform(post("/api/expenses/" + id + "/reject").with(csrf()).session(kv).contentType(JSON).content("{\"kommentar\":\" \"}"))
                .andExpect(status().isBadRequest());
        // eingereichte Ausgabe kann die Pflegekraft nicht selbst ändern
        mvc.perform(form("/api/expenses/" + id, data("25.00", LocalDate.now().toString(), ""), false).with(csrf())
                .with(req -> {
                    req.setMethod("PUT");
                    return req;
                }).session(pk)).andExpect(status().isConflict());

        mvc.perform(post("/api/expenses/" + id + "/reject").with(csrf()).session(kv).contentType(JSON).content("{\"kommentar\":\"Betrag falsch\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ABGELEHNT"));
        expectCash(70);
        mvc.perform(get("/api/expenses/mine").session(pk)).andExpect(jsonPath("$[0].ablehnungsKommentar").value("Betrag falsch"));

        mvc.perform(form("/api/expenses/" + id, data("25.00", LocalDate.now().toString(), ""), false).with(csrf())
                        .with(req -> {
                            req.setMethod("PUT");
                            return req;
                        }).session(pk))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EINGEREICHT"))
                .andExpect(jsonPath("$.belegAnzahl").value(1));
        expectCash(75);

        mvc.perform(post("/api/expenses/" + id + "/approve").with(csrf()).session(kv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GEPRUEFT"));
        mvc.perform(post("/api/expenses/" + id + "/reject").with(csrf()).session(kv).contentType(JSON).content("{\"kommentar\":\"zu spät\"}"))
                .andExpect(status().isConflict());
        mvc.perform(form("/api/expenses/" + id, data("20.00", LocalDate.now().toString(), ""), false).with(csrf())
                .with(req -> {
                    req.setMethod("PUT");
                    return req;
                }).session(pk)).andExpect(status().isConflict());
    }

    @Test
    void caregiverCannotReviewOrListEverything() throws Exception {
        String id = submit(pk, "10.00");
        mvc.perform(post("/api/expenses/" + id + "/approve").with(csrf()).session(pk)).andExpect(status().isForbidden());
        mvc.perform(post("/api/expenses/" + id + "/reject").with(csrf()).session(pk).contentType(JSON).content("{\"kommentar\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/expenses/pending").session(pk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/expenses").session(pk)).andExpect(status().isForbidden());
    }

    @Test
    void caregiverSeesOnlyOwnExpensesAndReceipts() throws Exception {
        String id = submit(pk, "10.00");
        MockHttpSession other = t.pflegekraft(g);
        mvc.perform(get("/api/expenses/mine").session(other)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/expenses/" + id).session(other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/receipts/" + id + "/0").session(other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/receipts/" + id + "/0").session(pk)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/api/receipts/" + id + "/0").session(kv)).andExpect(status().isOk());
        mvc.perform(get("/api/receipts/" + id + "/5").session(kv)).andExpect(status().isNotFound());
    }

    @Test
    void receiptsOfOtherGroupsAreNotReachable() throws Exception {
        String id = submit(pk, "10.00");
        MockHttpSession foreignKv = t.kassenverwalter(t.group("Fremd"));
        mvc.perform(get("/api/receipts/" + id + "/0").session(foreignKv)).andExpect(status().isNotFound());
        mvc.perform(get("/api/expenses/" + id).session(foreignKv)).andExpect(status().isNotFound());
    }

    @Test
    void managerCanFilterAllExpenses() throws Exception {
        String id = submit(pk, "10.00");
        submit(pk, "20.00");
        mvc.perform(post("/api/expenses/" + id + "/approve").with(csrf()).session(kv)).andExpect(status().isOk());
        mvc.perform(get("/api/expenses").session(kv)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/expenses?status=GEPRUEFT").session(kv)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/expenses?status=EINGEREICHT&kategorie=" + lebensmittel).session(kv)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/expenses?von=" + LocalDate.now().plusDays(1)).session(kv)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void kassenverwalterCanAlsoRecordCashExpense() throws Exception {
        submit(kv, "5.00");
        expectCash(95);
    }

    @Test
    void kontoausgabeReducesBankAccountOnly() throws Exception {
        mvc.perform(form("/api/ledger/kontoausgabe", data("250.00", LocalDate.now().toString(), ",\"zweck\":\"Waschmaschine\""), true)
                        .with(csrf()).session(kv))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("GEBUCHT"));
        mvc.perform(get("/api/balance").session(kv))
                .andExpect(jsonPath("$.vereinskonto").value(750))
                .andExpect(jsonPath("$.barkasse").value(100));
        mvc.perform(form("/api/ledger/kontoausgabe", data("10.00", LocalDate.now().toString(), ""), true).with(csrf()).session(pk))
                .andExpect(status().isForbidden());
        mvc.perform(form("/api/ledger/kontoausgabe", data("10.00", LocalDate.now().toString(), ""), false).with(csrf()).session(kv))
                .andExpect(status().isBadRequest());
    }
}

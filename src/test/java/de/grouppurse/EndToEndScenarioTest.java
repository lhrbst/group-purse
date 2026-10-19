package de.grouppurse;

import com.jayway.jsonpath.JsonPath;
import de.grouppurse.domain.Person;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Der komplette Ablauf aus der Spezifikation, nur über die REST-API und mit zwei getrennten Wohngruppen. */
class EndToEndScenarioTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    private String json(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String expenseJson(String amount, String categoryId) {
        return "{\"datum\":\"" + LocalDate.now() + "\",\"positionen\":[{\"kategorieId\":\"" + categoryId + "\",\"betrag\":" + amount + "}],\"haendler\":\"REWE\",\"zweck\":\"Einkauf Wochenende\"}";
    }

    @Test
    void fullLifecycleOfAGroupCash() throws Exception {
        // 1. Der Admin legt die Wohngruppe an und lädt einen Kassenverwalter ein
        Person adminPerson = t.person("admin", true);
        MockHttpSession admin = t.login(adminPerson.email);
        MvcResult created = mvc.perform(post("/api/admin/wohngruppen").with(csrf()).session(admin).contentType(JSON).content("{\"name\":\"WG Sonnenhof\"}"))
                .andExpect(status().isCreated()).andReturn();
        String groupId = JsonPath.read(json(created), "$.id");
        String code = JsonPath.read(json(created), "$.beitrittscode");

        String kvEmail = TestSupport.uniqueEmail("kassenverwalter");
        MvcResult invite = mvc.perform(post("/api/admin/wohngruppen/" + groupId + "/kassenverwalter-einladung").with(csrf()).session(admin)
                        .contentType(JSON).content("{\"email\":\"" + kvEmail + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String link = JsonPath.read(json(invite), "$.link");
        String token = link.substring(link.lastIndexOf('/') + 1);

        // 2. Der Kassenverwalter nimmt die Einladung an
        MockHttpSession kv = (MockHttpSession) mvc.perform(post("/api/einladungen/" + token + "/einloesen").with(csrf()).contentType(JSON)
                        .content("{\"name\":\"Karla Kasse\",\"password\":\"sicheres-passwort-1\",\"datenschutzZugestimmt\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeRole").value("KASSENVERWALTER"))
                .andReturn().getRequest().getSession();

        // 3. Anfangsbestände
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(JSON).content("{\"konto\":\"VEREINSKONTO\",\"betrag\":1000}")).andExpect(status().isOk());
        mvc.perform(post("/api/ledger/anfangsbestand").with(csrf()).session(kv).contentType(JSON).content("{\"konto\":\"BARKASSE\",\"betrag\":100}")).andExpect(status().isOk());

        // 4. Eine Pflegekraft registriert sich per Code und wird freigeschaltet
        String pkEmail = TestSupport.uniqueEmail("pflegekraft");
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(JSON)
                        .content("{\"name\":\"Paula Pflege\",\"email\":\"" + pkEmail + "\",\"password\":\"noch-ein-passwort-2\",\"beitrittscode\":\"" + code + "\",\"datenschutzZugestimmt\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mitgliedschaften[0].status").value("WARTEND"));
        String members = json(mvc.perform(get("/api/members").session(kv)).andExpect(status().isOk()).andReturn());
        List<String> ids = JsonPath.read(members, "$[?(@.email=='" + pkEmail + "')].mitgliedschaftId");
        mvc.perform(post("/api/members/" + ids.get(0) + "/freischalten").with(csrf()).session(kv)).andExpect(status().isOk());
        MockHttpSession pk = t.login(pkEmail, "noch-ein-passwort-2");
        mvc.perform(get("/api/balance").session(pk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.barkasse").value(100))
                .andExpect(jsonPath("$.vereinskonto").doesNotExist());

        // 5. Ausgabe erfassen, ablehnen, korrigieren, prüfen
        List<String> cats = JsonPath.read(json(mvc.perform(get("/api/categories").session(pk)).andReturn()), "$[?(@.name=='Haushalt und Drogerie')].id");
        String category = cats.get(0);
        MvcResult submitted = mvc.perform(multipart("/api/expenses")
                        .file(new MockMultipartFile("data", "", JSON, expenseJson("30.00", category).getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("photos", "beleg.jpg", "image/jpeg", JPEG))
                        .with(csrf()).session(pk))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("EINGEREICHT")).andReturn();
        String expenseId = JsonPath.read(json(submitted), "$.id");
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.barkasse").value(70));

        mvc.perform(post("/api/expenses/" + expenseId + "/reject").with(csrf()).session(kv).contentType(JSON).content("{\"kommentar\":\"Betrag stimmt nicht\"}"))
                .andExpect(status().isOk());
        // Foto unbrauchbar: neues Foto hochladen (die KI ist in diesem Test aus, also trägt der Kassenverwalter die Daten ein)
        mvc.perform(multipart("/api/expenses/" + expenseId + "/photos").file(new MockMultipartFile("photos", "neu.jpg", "image/jpeg", JPEG))
                        .with(csrf()).with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }).session(pk))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MANUELL"));
        mvc.perform(put("/api/expenses/" + expenseId + "/details").with(csrf()).session(kv).contentType(JSON).content(expenseJson("28.50", category)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EINGEREICHT"));
        mvc.perform(post("/api/expenses/" + expenseId + "/approve").with(csrf()).session(kv)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/manager").session(kv))
                .andExpect(jsonPath("$.barkasse").value(71.5))
                .andExpect(jsonPath("$.eingereichtAnzahl").value(0));

        // 6. Barkasse auffüllen
        mvc.perform(post("/api/ledger/auffuellung").with(csrf()).session(kv).contentType(JSON).content("{\"betrag\":100,\"uebergebenAn\":\"Paula Pflege\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/balance").session(kv))
                .andExpect(jsonPath("$.barkasse").value(171.5))
                .andExpect(jsonPath("$.vereinskonto").value(900));

        // 7. Elternbeitrag buchen
        String month = java.time.YearMonth.now().toString();
        MvcResult family = mvc.perform(post("/api/families").with(csrf()).session(kv).contentType(JSON)
                        .content("{\"name\":\"Müller\",\"bewohnerKuerzel\":\"T.\",\"faelligkeitstag\":5,\"sollbetrag\":50,\"gueltigAb\":\"" + month + "-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        String familyId = JsonPath.read(json(family), "$.id");
        mvc.perform(post("/api/contributions").with(csrf()).session(kv).contentType(JSON)
                .content("{\"familieId\":\"" + familyId + "\",\"betrag\":50,\"monate\":[\"" + month + "\"]}")).andExpect(status().isCreated());
        mvc.perform(get("/api/balance").session(kv)).andExpect(jsonPath("$.vereinskonto").value(950));
        mvc.perform(get("/api/contributions/grid?von=" + month + "&bis=" + month).session(kv))
                .andExpect(jsonPath("$.zeilen[0].cells[0].status").value("BEZAHLT"));

        // 8. Eine zweite Wohngruppe sieht nichts davon
        var other = t.group("Andere WG");
        MockHttpSession otherKv = t.kassenverwalter(other);
        mvc.perform(get("/api/ledger").session(otherKv)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/families").session(otherKv)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/receipts/" + expenseId + "/0").session(otherKv)).andExpect(status().isNotFound());
        mvc.perform(post("/api/me/active-group").with(csrf()).session(pk).contentType(JSON)
                .content("{\"wohngruppeId\":\"" + other.id + "\"}")).andExpect(status().isForbidden());

        // 9. Die Pflegekraft darf die Verwaltung nicht benutzen
        mvc.perform(put("/api/group/settings").with(csrf()).session(pk).contentType(JSON).content("{\"mindestbetragKasse\":1}")).andExpect(status().isForbidden());
    }
}

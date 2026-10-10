package de.grouppurse.receipt;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.receipt.enabled=true")
class ReceiptControllerTest extends AbstractIntegrationTest {
    @MockitoBean ReceiptModelClient client;

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @Test
    void returnsSuggestionForActiveMember() throws Exception {
        var g = t.group("KI");
        MockHttpSession pk = t.pflegekraft(g);
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ledger/anfangsbestand")
                .with(csrf()).session(kv).contentType("application/json")
                .content("{\"konto\":\"BARKASSE\",\"betrag\":100}")).andExpect(status().isOk());
        when(client.extract(any(), any(), any(), any())).thenReturn(Optional.of(
                new RawReceipt("REWE", LocalDate.now().toString(), new BigDecimal("12.34"), "Lebensmittel")));
        mvc.perform(multipart("/api/receipts/recognize").file(new MockMultipartFile("photo", "b.jpg", "image/jpeg", JPEG))
                        .with(csrf()).session(pk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.haendler").value("REWE"))
                .andExpect(jsonPath("$.betrag").value(12.34));
    }

    @Test
    void noContentWhenModelGivesNothing() throws Exception {
        var g = t.group("KI2");
        MockHttpSession pk = t.pflegekraft(g);
        when(client.extract(any(), any(), any(), any())).thenReturn(Optional.empty());
        mvc.perform(multipart("/api/receipts/recognize").file(new MockMultipartFile("photo", "b.jpg", "image/jpeg", JPEG))
                .with(csrf()).session(pk)).andExpect(status().isNoContent());
    }

    @Test
    void rejectsNonImages() throws Exception {
        var g = t.group("KI3");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(multipart("/api/receipts/recognize").file(new MockMultipartFile("photo", "b.txt", "text/plain", "hallo".getBytes()))
                .with(csrf()).session(pk)).andExpect(status().isBadRequest());
    }
}

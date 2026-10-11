package de.grouppurse.metrics;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.receipt.ReceiptModelClient;
import de.grouppurse.receipt.ReceiptModelClient.RawPosition;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import de.grouppurse.receipt.ReceiptProcessor;
import io.micrometer.core.instrument.MeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Die fachlichen Metriken steigen an den richtigen Stellen. */
@TestPropertySource(properties = "app.receipt.enabled=true")
class MetricsIntegrationTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    @MockitoBean ReceiptModelClient client;
    @Autowired ReceiptProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;

    Wohngruppe g;
    MockHttpSession kv;
    MockHttpSession pk;

    @BeforeEach
    void setUp() throws Exception {
        g = t.group("Metriken");
        kv = t.kassenverwalter(g);
        pk = t.pflegekraft(g);
        jdbc.update("update buchung set status = 'MANUELL' where status = 'VERARBEITUNG' and wohngruppe_id <> ?", g.id);
    }

    double count(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    double timerCount(String name, String... tags) {
        var timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    void capture() throws Exception {
        mvcCapture(new MockMultipartFile("photos", "b.jpg", "image/jpeg", JPEG)).andExpect(status().isCreated());
    }

    org.springframework.test.web.servlet.ResultActions mvcCapture(MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/expenses/capture").file(file).with(csrf()).session(pk));
    }

    @Test
    void failedLoginIsCounted() throws Exception {
        double before = count("gk.login", "outcome", "failure");
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(JSON)
                .content("{\"email\":\"niemand@example.org\",\"password\":\"falsch-falsch-1\"}")).andExpect(status().isUnauthorized());
        assertThat(count("gk.login", "outcome", "failure")).isEqualTo(before + 1);
    }

    @Test
    void rejectedUploadIsCountedByReason() throws Exception {
        double before = count("gk.upload.rejected", "reason", "bad_type");
        mvcCapture(new MockMultipartFile("photos", "x.jpg", "image/jpeg", "kein Bild".getBytes())).andExpect(status().isBadRequest());
        assertThat(count("gk.upload.rejected", "reason", "bad_type")).isEqualTo(before + 1);
    }

    @Test
    void successfulReceiptReadingIsVisibleInPipelineAndLedgerMetrics() throws Exception {
        double usable = count("gk.receipt.ai.results", "result", "usable");
        double toSubmitted = count("gk.expense.transitions", "from", "VERARBEITUNG", "to", "EINGEREICHT");
        double fromNew = count("gk.expense.transitions", "from", "NEU", "to", "VERARBEITUNG");
        double bookings = count("gk.ledger.bookings", "type", "AUSGABE");
        double e2e = timerCount("gk.receipt.end_to_end", "outcome", "EINGEREICHT");
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(new RawReceipt("REWE", LocalDate.now().toString(),
                new BigDecimal("12.00"), List.of(new RawPosition("Haushalt", new BigDecimal("12.00"))))));

        capture();
        assertThat(processor.runOnce()).isEqualTo(1);

        assertThat(count("gk.expense.transitions", "from", "NEU", "to", "VERARBEITUNG")).isEqualTo(fromNew + 1);
        assertThat(count("gk.ledger.bookings", "type", "AUSGABE")).isEqualTo(bookings + 1);
        assertThat(count("gk.receipt.ai.results", "result", "usable")).isEqualTo(usable + 1);
        assertThat(count("gk.expense.transitions", "from", "VERARBEITUNG", "to", "EINGEREICHT")).isEqualTo(toSubmitted + 1);
        assertThat(timerCount("gk.receipt.end_to_end", "outcome", "EINGEREICHT")).isEqualTo(e2e + 1);
    }

    @Test
    void uncertainReceiptCountsFallbackAndGaveUp() throws Exception {
        double fallback = count("gk.receipt.ai.fallback");
        double gaveUp = count("gk.receipt.ai.gave_up", "reason", "uncertain");
        double manual = timerCount("gk.receipt.end_to_end", "outcome", "MANUELL");
        when(client.extract(any(), any(), any())).thenReturn(Optional.of(new RawReceipt(3, null, null, null, null, Boolean.FALSE, "abgeschnitten")));

        capture();
        assertThat(processor.runOnce()).isEqualTo(1);

        assertThat(count("gk.receipt.ai.fallback")).isEqualTo(fallback + 1);
        assertThat(count("gk.receipt.ai.gave_up", "reason", "uncertain")).isEqualTo(gaveUp + 1);
        assertThat(timerCount("gk.receipt.end_to_end", "outcome", "MANUELL")).isEqualTo(manual + 1);
    }

    @Test
    void registeredMetricsContainNoMoneyOrPersonLabels() throws Exception {
        capture();
        registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("gk."))
                .forEach(m -> m.getId().getTags()
                        .forEach(tag -> assertThat(tag.getKey()).isNotIn("gruppe", "wohngruppe", "email", "name", "betrag", "person")));
    }
}

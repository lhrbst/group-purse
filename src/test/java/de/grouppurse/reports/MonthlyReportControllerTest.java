package de.grouppurse.reports;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonthlyReportControllerTest extends AbstractIntegrationTest {
    @Autowired MeterRegistry registry;

    @Test
    void managerGetsTheReportAsJson() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10").session(kv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monat").value("2026-10"))
                .andExpect(jsonPath("$.gruppe").value("Bericht"))
                .andExpect(jsonPath("$.summe").value(0))
                .andExpect(jsonPath("$.zusammenfassung").value("Keine Ausgaben in diesem Monat."))
                .andExpect(jsonPath("$.belege.length()").value(0));
    }

    @Test
    void managerGetsAPdfWithDownloadHeaders() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        double before = registry.counter("gk.report.pdf", "outcome", "ok").count();
        byte[] body = mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10").session(kv))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Gruppenkasse-2026-10.pdf\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
        assertThat(registry.counter("gk.report.pdf", "outcome", "ok").count()).isEqualTo(before + 1);
    }

    @Test
    void caregiverIsForbiddenForBoth() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10").session(pk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10").session(pk)).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/reports/monthly").param("monat", "2026-10")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/reports/monthly.pdf").param("monat", "2026-10")).andExpect(status().is4xxClientError());
    }

    @Test
    void invalidAndFutureMonthsAreBadRequests() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        for (String path : new String[]{"/api/reports/monthly", "/api/reports/monthly.pdf"}) {
            for (String bad : new String[]{"2026-13", "abc", "2026-1", "2026-10-01", "", YearMonth.now().plusMonths(1).toString()}) {
                mvc.perform(get(path).param("monat", bad).session(kv)).andExpect(status().isBadRequest());
            }
            mvc.perform(get(path).session(kv)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theCurrentMonthIsAllowed() throws Exception {
        Wohngruppe g = t.group("Bericht");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(get("/api/reports/monthly").param("monat", YearMonth.now().toString()).session(kv)).andExpect(status().isOk());
    }
}

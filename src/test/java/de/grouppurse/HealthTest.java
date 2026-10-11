package de.grouppurse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Im MockMvc-Test gibt es keinen eigenen Management-Port; dort liegt der Health-Endpunkt auf dem Hauptport (echt getestet im E2E). */
@TestPropertySource(properties = "management.server.port=")
class HealthTest extends AbstractIntegrationTest {
    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")));
    }
}

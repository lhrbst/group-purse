package de.grouppurse.notify;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.repo.PushSubscriptionRepository;
import de.grouppurse.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PushControllerTest extends AbstractIntegrationTest {
    @Autowired PushSubscriptionRepository subs;

    @Test
    void subscribeIsIdempotentAndUnsubscribeRemoves() throws Exception {
        var g = t.group("Push");
        MockHttpSession pk = t.pflegekraft(g);
        String body = "{\"endpoint\":\"https://push.example/abc\",\"p256dh\":\"key\",\"auth\":\"auth\"}";
        mvc.perform(post("/api/push/subscribe").with(csrf()).session(pk).contentType("application/json").content(body)).andExpect(status().isNoContent());
        mvc.perform(post("/api/push/subscribe").with(csrf()).session(pk).contentType("application/json").content(body)).andExpect(status().isNoContent());
        assertThat(TenantContext.callAs(g.id, () -> subs.findAll())).hasSize(1);
        mvc.perform(delete("/api/push/subscribe?endpoint=https://push.example/abc").with(csrf()).session(pk)).andExpect(status().isNoContent());
        assertThat(TenantContext.callAs(g.id, () -> subs.findAll())).isEmpty();
    }

    @Test
    void publicKeyIsEmptyWithoutConfiguration() throws Exception {
        var g = t.group("Push2");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/push/public-key").session(pk)).andExpect(status().isOk()).andExpect(jsonPath("$.publicKey").value(""));
    }
}

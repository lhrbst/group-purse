package de.grouppurse.web;

import de.grouppurse.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class SpaAndOpenApiTest extends AbstractIntegrationTest {

    @Test
    void clientRoutesFallBackToIndexHtml() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()); // Willkommensseite leitet per Forward auf index.html
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("SPA-INDEX")));
        mvc.perform(get("/passwort-neu/abc")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("SPA-INDEX")));
        mvc.perform(get("/manager/kasse")).andExpect(status().isOk());
    }

    @Test
    void missingFilesAndUnknownApiPathsAre404Or401() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/favicon-missing.ico")).andExpect(status().isNotFound());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/")).andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void openApiDescribesTheApiAndCanBeExported() throws Exception {
        String json = mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json).contains("/api/expenses").contains("/api/ledger/auffuellung").contains("/api/contributions/grid")
                .contains("/api/export/annual-report.pdf");
        String out = System.getProperty("openapi.out");
        if (out != null) {
            Path p = Path.of(out).toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, json, StandardCharsets.UTF_8);
        }
    }
}

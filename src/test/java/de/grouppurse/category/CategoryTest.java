package de.grouppurse.category;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";

    private String idOf(MockHttpSession s, String name) throws Exception {
        String body = mvc.perform(get("/api/categories?alle=true").session(s)).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\\{\"id\":\"([^\"]+)\",\"name\":\"" + name + "\".*", "$1");
    }

    @Test
    void defaultListHasEightCategories() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/categories").session(pk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void hidingIsPerGroup() throws Exception {
        Wohngruppe a = t.group("A");
        Wohngruppe b = t.group("B");
        MockHttpSession kvA = t.kassenverwalter(a);
        MockHttpSession kvB = t.kassenverwalter(b);
        String id = idOf(kvA, "Sonstiges");
        mvc.perform(put("/api/categories/" + id + "/hidden").with(csrf()).session(kvA).contentType(JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(7));
        mvc.perform(get("/api/categories?alle=true").session(kvA)).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(get("/api/categories").session(kvB)).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(put("/api/categories/" + id + "/hidden").with(csrf()).session(kvA).contentType(JSON).content("{\"hidden\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void ownCategoryOnlyVisibleInOwnGroupAndDuplicatesRejected() throws Exception {
        Wohngruppe a = t.group("A");
        Wohngruppe b = t.group("B");
        MockHttpSession kvA = t.kassenverwalter(a);
        MockHttpSession kvB = t.kassenverwalter(b);
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"Garten\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/api/categories").session(kvB)).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"garten\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"lebensmittel\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void caregiverCannotManageCategories() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(post("/api/categories").with(csrf()).session(pk).contentType(JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/categories?alle=true").session(pk)).andExpect(status().isForbidden());
    }
}

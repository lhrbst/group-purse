package de.grouppurse.category;

import com.jayway.jsonpath.JsonPath;
import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Wohngruppe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryTest extends AbstractIntegrationTest {
    private static final String JSON = "application/json";
    private static final int MAIN = 8;
    private static final int FOOD_SUBS = 10;

    private String idOf(MockHttpSession s, String name) throws Exception {
        String body = mvc.perform(get("/api/categories?alle=true").session(s)).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    @Test
    void defaultListHasMainCategoriesAndFoodSubcategories() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        mvc.perform(get("/api/categories").session(pk)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS))
                .andExpect(jsonPath("$[?(@.name=='Obst und Gemüse')].parentId").isNotEmpty())
                .andExpect(jsonPath("$[?(@.name=='Lebensmittel')].id").isNotEmpty());
    }

    @Test
    void foodSubcategoriesFollowTheirParentInTheList() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession pk = t.pflegekraft(g);
        String body = mvc.perform(get("/api/categories").session(pk)).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$[*].name");
        int parent = names.indexOf("Lebensmittel");
        org.assertj.core.api.Assertions.assertThat(names.subList(parent + 1, parent + 1 + FOOD_SUBS)).contains("Obst und Gemüse", "Getränke", "Sonstige Lebensmittel");
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
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS - 1));
        mvc.perform(get("/api/categories?alle=true").session(kvA)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS));
        mvc.perform(get("/api/categories").session(kvB)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS));
        mvc.perform(put("/api/categories/" + id + "/hidden").with(csrf()).session(kvA).contentType(JSON).content("{\"hidden\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS));
    }

    @Test
    void hidingTheParentHidesItsSubcategories() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(put("/api/categories/" + idOf(kv, "Lebensmittel") + "/hidden").with(csrf()).session(kv).contentType(JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kv)).andExpect(jsonPath("$.length()").value(MAIN - 1));
    }

    @Test
    void hidingSingleSubcategoryKeepsTheRest() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        mvc.perform(put("/api/categories/" + idOf(kv, "Getränke") + "/hidden").with(csrf()).session(kv).contentType(JSON).content("{\"hidden\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kv)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS - 1));
    }

    @Test
    void ownCategoryOnlyVisibleInOwnGroupAndDuplicatesRejected() throws Exception {
        Wohngruppe a = t.group("A");
        Wohngruppe b = t.group("B");
        MockHttpSession kvA = t.kassenverwalter(a);
        MockHttpSession kvB = t.kassenverwalter(b);
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"Garten\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/categories").session(kvA)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS + 1));
        mvc.perform(get("/api/categories").session(kvB)).andExpect(jsonPath("$.length()").value(MAIN + FOOD_SUBS));
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"garten\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/categories").with(csrf()).session(kvA).contentType(JSON).content("{\"name\":\"lebensmittel\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ownSubcategoryUnderAMainCategoryButOnlyOneLevel() throws Exception {
        Wohngruppe g = t.group("G");
        MockHttpSession kv = t.kassenverwalter(g);
        String haushalt = idOf(kv, "Haushalt");
        String created = mvc.perform(post("/api/categories").with(csrf()).session(kv).contentType(JSON)
                        .content("{\"name\":\"Putzmittel\",\"parentId\":\"" + haushalt + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(haushalt)).andReturn().getResponse().getContentAsString();
        String sub = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/categories").with(csrf()).session(kv).contentType(JSON)
                .content("{\"name\":\"Tiefer\",\"parentId\":\"" + sub + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/categories").with(csrf()).session(kv).contentType(JSON)
                .content("{\"name\":\"Waise\",\"parentId\":\"00000000-0000-0000-0000-000000000001\"}")).andExpect(status().isBadRequest());
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

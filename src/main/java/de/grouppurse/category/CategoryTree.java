package de.grouppurse.category;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nachschlagehilfe über die (höchstens zweistufige) Kategorienstruktur einer Wohngruppe. */
public final class CategoryTree {
    private final Map<UUID, CategoryDto> byId = new HashMap<>();

    public CategoryTree(List<CategoryDto> all) {
        all.forEach(c -> byId.put(c.id(), c));
    }

    public String name(UUID id) {
        CategoryDto c = id == null ? null : byId.get(id);
        return c == null ? "Ohne Kategorie" : c.name();
    }

    public UUID mainId(UUID id) {
        CategoryDto c = id == null ? null : byId.get(id);
        return c == null || c.parentId() == null ? id : c.parentId();
    }

    public String mainName(UUID id) {
        return name(mainId(id));
    }

    public boolean isSameOrChildOf(UUID id, UUID ancestor) {
        if (id == null || ancestor == null) {
            return false;
        }
        return id.equals(ancestor) || ancestor.equals(mainId(id));
    }
}

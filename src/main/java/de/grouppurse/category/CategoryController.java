package de.grouppurse.category;

import de.grouppurse.security.Access;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    public record CreateCategory(@NotBlank String name, UUID parentId) {
    }

    public record HiddenRequest(boolean hidden) {
    }

    private final Access access;
    private final CategoryService categories;

    public CategoryController(Access access, CategoryService categories) {
        this.access = access;
        this.categories = categories;
    }

    /** Alle sichtbaren Kategorien (flach, mit parentId). Kassenverwalter können mit alle=true auch ausgeblendete laden. */
    @GetMapping
    public List<CategoryDto> list(@RequestParam(defaultValue = "false") boolean alle) {
        if (alle) {
            access.requireKassenverwalter();
            return categories.all();
        }
        access.requireActiveMember();
        return categories.visible();
    }

    @PostMapping
    public CategoryDto create(@RequestBody CreateCategory r) {
        access.requireKassenverwalter();
        return categories.add(r.name(), r.parentId());
    }

    @PutMapping("/{id}/hidden")
    public void hide(@PathVariable UUID id, @RequestBody HiddenRequest r) {
        access.requireKassenverwalter();
        categories.hide(id, r.hidden());
    }
}

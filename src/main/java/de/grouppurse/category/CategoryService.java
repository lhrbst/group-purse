package de.grouppurse.category;

import de.grouppurse.audit.AuditService;
import de.grouppurse.domain.KategorieGlobal;
import de.grouppurse.domain.KategorieGruppe;
import de.grouppurse.repo.KategorieGlobalRepository;
import de.grouppurse.repo.KategorieGruppeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Globale Standardkategorien plus gruppeneigene, höchstens zwei Ebenen (Hauptkategorie → Unterkategorie).
 * Jede Kategorie lässt sich je Gruppe ausblenden. Auf Belegpositionen wählbar sind nur Kategorien ohne sichtbare Unterkategorien.
 */
@Service
public class CategoryService {
    private final KategorieGlobalRepository globals;
    private final KategorieGruppeRepository own;
    private final AuditService audit;

    public CategoryService(KategorieGlobalRepository globals, KategorieGruppeRepository own, AuditService audit) {
        this.globals = globals;
        this.own = own;
        this.audit = audit;
    }

    /** Alle Kategorien der aktiven Gruppe inkl. ausgeblendeter, sortiert: Hauptkategorie, direkt darunter ihre Unterkategorien. */
    @Transactional(readOnly = true)
    public List<CategoryDto> all() {
        Map<UUID, KategorieGruppe> overrides = new HashMap<>();
        List<KategorieGruppe> ownRows = own.findAll();
        for (KategorieGruppe k : ownRows) {
            if (k.globalRef != null) {
                overrides.put(k.globalRef, k);
            }
        }
        List<CategoryDto> result = new ArrayList<>();
        for (KategorieGlobal g : globals.findAll()) {
            KategorieGruppe o = overrides.get(g.id);
            result.add(new CategoryDto(g.id, g.name, true, o != null && o.ausgeblendet, g.parentId));
        }
        for (KategorieGruppe k : ownRows) {
            if (k.globalRef == null) {
                result.add(new CategoryDto(k.id, k.name, false, k.ausgeblendet, k.parentId));
            }
        }
        Map<UUID, String> names = result.stream().collect(Collectors.toMap(CategoryDto::id, CategoryDto::name));
        result.sort(Comparator
                .comparing((CategoryDto c) -> (c.parentId() == null ? c.name() : names.getOrDefault(c.parentId(), c.name())).toLowerCase())
                .thenComparing(c -> c.parentId() == null ? 0 : 1)
                .thenComparing(CategoryDto::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Sichtbare Kategorien: nicht ausgeblendet, und bei Unterkategorien auch die Hauptkategorie sichtbar. */
    @Transactional(readOnly = true)
    public List<CategoryDto> visible() {
        List<CategoryDto> all = all();
        Set<UUID> visibleMain = all.stream().filter(c -> c.parentId() == null && !c.ausgeblendet()).map(CategoryDto::id).collect(Collectors.toSet());
        return all.stream()
                .filter(c -> !c.ausgeblendet())
                .filter(c -> c.parentId() == null || visibleMain.contains(c.parentId()))
                .toList();
    }

    /** Auf Positionen wählbar: sichtbar und ohne sichtbare Unterkategorie. */
    @Transactional(readOnly = true)
    public List<CategoryDto> leaves() {
        List<CategoryDto> visible = visible();
        Set<UUID> parents = visible.stream().map(CategoryDto::parentId).filter(p -> p != null).collect(Collectors.toSet());
        return visible.stream().filter(c -> !parents.contains(c.id())).toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return id != null && leaves().stream().anyMatch(c -> c.id().equals(id));
    }

    @Transactional(readOnly = true)
    public CategoryTree tree() {
        return new CategoryTree(all());
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> namesById() {
        Map<UUID, String> m = new HashMap<>();
        all().forEach(c -> m.put(c.id(), c.name()));
        return m;
    }

    @Transactional
    public CategoryDto add(String rawName, UUID parentId) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Name fehlt");
        }
        String name = rawName.trim();
        List<CategoryDto> all = all();
        if (all.stream().anyMatch(c -> c.name().equalsIgnoreCase(name))) {
            throw new IllegalStateException("Eine Kategorie mit diesem Namen existiert bereits");
        }
        if (parentId != null) {
            CategoryDto parent = all.stream().filter(c -> c.id().equals(parentId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Oberkategorie nicht gefunden"));
            if (parent.parentId() != null) {
                throw new IllegalArgumentException("Unterkategorien gibt es nur eine Ebene tief");
            }
        }
        KategorieGruppe k = new KategorieGruppe();
        k.name = name;
        k.parentId = parentId;
        k = own.save(k);
        audit.log("Kategorie angelegt", "Kategorie", k.id, null, name);
        return new CategoryDto(k.id, k.name, false, false, parentId);
    }

    @Transactional
    public void hide(UUID id, boolean hidden) {
        if (globals.existsById(id)) {
            KategorieGruppe k = own.findByGlobalRef(id).orElseGet(() -> {
                KategorieGruppe n = new KategorieGruppe();
                n.globalRef = id;
                return n;
            });
            k.ausgeblendet = hidden;
            own.save(k);
        } else {
            KategorieGruppe k = own.findById(id).orElseThrow(() -> new IllegalArgumentException("Kategorie nicht gefunden"));
            k.ausgeblendet = hidden;
        }
        audit.log(hidden ? "Kategorie ausgeblendet" : "Kategorie eingeblendet", "Kategorie", id, null, hidden);
    }
}

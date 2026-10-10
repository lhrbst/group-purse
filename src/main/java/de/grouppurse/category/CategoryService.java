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
import java.util.UUID;

/** Globale Standardkategorien plus gruppeneigene; globale lassen sich je Gruppe ausblenden. */
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

    /** Alle Kategorien der aktiven Gruppe inkl. ausgeblendeter (für Verwaltung und Anzeige alter Buchungen). */
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
            result.add(new CategoryDto(g.id, g.name, true, o != null && o.ausgeblendet));
        }
        for (KategorieGruppe k : ownRows) {
            if (k.globalRef == null) {
                result.add(new CategoryDto(k.id, k.name, false, k.ausgeblendet));
            }
        }
        result.sort(Comparator.comparing(CategoryDto::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> visible() {
        return all().stream().filter(c -> !c.ausgeblendet()).toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return id != null && visible().stream().anyMatch(c -> c.id().equals(id));
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> namesById() {
        Map<UUID, String> m = new HashMap<>();
        all().forEach(c -> m.put(c.id(), c.name()));
        return m;
    }

    @Transactional
    public CategoryDto add(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Name fehlt");
        }
        String name = rawName.trim();
        if (all().stream().anyMatch(c -> c.name().equalsIgnoreCase(name))) {
            throw new IllegalStateException("Eine Kategorie mit diesem Namen existiert bereits");
        }
        KategorieGruppe k = new KategorieGruppe();
        k.name = name;
        k = own.save(k);
        audit.log("Kategorie angelegt", "Kategorie", k.id, null, name);
        return new CategoryDto(k.id, k.name, false, false);
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

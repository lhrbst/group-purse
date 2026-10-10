package de.grouppurse.admin;

import de.grouppurse.admin.AdminDtos.WohngruppeDto;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.WohngruppeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WohngruppeService {
    private final WohngruppeRepository gruppen;

    public WohngruppeService(WohngruppeRepository gruppen) {
        this.gruppen = gruppen;
    }

    public static WohngruppeDto toDto(Wohngruppe g, boolean withCode) {
        return new WohngruppeDto(g.id, g.name, withCode ? g.beitrittscode : null, g.mindestbetragKasse);
    }

    @Transactional
    public Wohngruppe create(String name) {
        Wohngruppe g = new Wohngruppe();
        g.name = name.trim();
        g.beitrittscode = uniqueCode();
        return gruppen.save(g);
    }

    @Transactional
    public String regenerateCode(UUID wohngruppeId) {
        Wohngruppe g = gruppen.findById(wohngruppeId).orElseThrow();
        g.beitrittscode = uniqueCode();
        return g.beitrittscode;
    }

    @Transactional(readOnly = true)
    public List<Wohngruppe> list() {
        return gruppen.findAll().stream().sorted((a, b) -> a.name.compareToIgnoreCase(b.name)).toList();
    }

    private String uniqueCode() {
        for (int i = 0; i < 20; i++) {
            String c = BeitrittscodeGenerator.generate();
            if (!gruppen.existsByBeitrittscode(c)) {
                return c;
            }
        }
        throw new IllegalStateException("Kein freier Beitrittscode gefunden");
    }
}

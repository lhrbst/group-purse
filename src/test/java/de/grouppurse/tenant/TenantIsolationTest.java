package de.grouppurse.tenant;

import de.grouppurse.AbstractIntegrationTest;
import de.grouppurse.domain.Familie;
import de.grouppurse.domain.Wohngruppe;
import de.grouppurse.repo.FamilieRepository;
import de.grouppurse.repo.WohngruppeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIsolationTest extends AbstractIntegrationTest {
    @Autowired WohngruppeRepository gruppen;
    @Autowired FamilieRepository familien;

    private UUID gruppe(String name) {
        Wohngruppe g = new Wohngruppe();
        g.name = name;
        g.beitrittscode = UUID.randomUUID().toString().substring(0, 8);
        return gruppen.save(g).id;
    }

    private UUID familie(UUID tenant, String name) {
        return TenantContext.callAs(tenant, () -> {
            Familie f = new Familie();
            f.name = name;
            return familien.save(f).id;
        });
    }

    @Test
    void queriesAreFilteredByTenant() {
        UUID a = gruppe("A");
        UUID b = gruppe("B");
        UUID famA = familie(a, "Familie A");
        familie(b, "Familie B");

        TenantContext.runAs(a, () -> {
            assertThat(familien.findAll()).extracting(f -> f.name).containsExactly("Familie A");
            assertThat(familien.findById(famA)).isPresent();
        });
        TenantContext.runAs(b, () -> {
            assertThat(familien.findAll()).extracting(f -> f.name).containsExactly("Familie B");
            assertThat(familien.findById(famA)).isEmpty();
        });
    }

    @Test
    void writingWithoutTenantFails() {
        TenantContext.clear();
        Familie f = new Familie();
        f.name = "Ohne Gruppe";
        assertThatThrownBy(() -> familien.save(f)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readingWithoutTenantSeesNothing() {
        UUID a = gruppe("A2");
        familie(a, "Familie A2");
        TenantContext.clear();
        assertThat(familien.findAll()).isEmpty();
    }
}

package de.grouppurse.repo;

import de.grouppurse.domain.KategorieGruppe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KategorieGruppeRepository extends JpaRepository<KategorieGruppe, UUID> {
    Optional<KategorieGruppe> findByGlobalRef(UUID globalRef);
}

package de.grouppurse.repo;

import de.grouppurse.domain.KategorieGlobal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KategorieGlobalRepository extends JpaRepository<KategorieGlobal, UUID> {
}

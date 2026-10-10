package de.grouppurse.repo;

import de.grouppurse.domain.Wohngruppe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WohngruppeRepository extends JpaRepository<Wohngruppe, UUID> {
    Optional<Wohngruppe> findByBeitrittscode(String beitrittscode);

    boolean existsByBeitrittscode(String beitrittscode);
}

package de.grouppurse.repo;

import de.grouppurse.domain.Einladung;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EinladungRepository extends JpaRepository<Einladung, UUID> {
    Optional<Einladung> findByToken(String token);
}

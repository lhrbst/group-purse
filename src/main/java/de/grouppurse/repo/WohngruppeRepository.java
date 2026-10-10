package de.grouppurse.repo;

import de.grouppurse.domain.Wohngruppe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WohngruppeRepository extends JpaRepository<Wohngruppe, UUID> {
}

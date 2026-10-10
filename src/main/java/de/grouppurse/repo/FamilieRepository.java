package de.grouppurse.repo;

import de.grouppurse.domain.Familie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FamilieRepository extends JpaRepository<Familie, UUID> {
}

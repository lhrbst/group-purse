package de.grouppurse.repo;

import de.grouppurse.domain.Mitgliedschaft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MitgliedschaftRepository extends JpaRepository<Mitgliedschaft, UUID> {
}

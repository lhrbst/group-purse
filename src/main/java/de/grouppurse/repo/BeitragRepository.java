package de.grouppurse.repo;

import de.grouppurse.domain.Beitrag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BeitragRepository extends JpaRepository<Beitrag, UUID> {
}

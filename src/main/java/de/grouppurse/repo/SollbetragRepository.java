package de.grouppurse.repo;

import de.grouppurse.domain.Sollbetrag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SollbetragRepository extends JpaRepository<Sollbetrag, UUID> {
}

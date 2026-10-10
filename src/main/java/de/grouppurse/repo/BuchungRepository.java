package de.grouppurse.repo;

import de.grouppurse.domain.Buchung;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BuchungRepository extends JpaRepository<Buchung, UUID> {
}

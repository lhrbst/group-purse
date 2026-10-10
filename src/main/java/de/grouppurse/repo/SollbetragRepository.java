package de.grouppurse.repo;

import de.grouppurse.domain.Sollbetrag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SollbetragRepository extends JpaRepository<Sollbetrag, UUID> {
    List<Sollbetrag> findByFamilieIdOrderByGueltigAbAsc(UUID familieId);

    Optional<Sollbetrag> findByFamilieIdAndGueltigAb(UUID familieId, LocalDate gueltigAb);
}

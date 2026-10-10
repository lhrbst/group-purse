package de.grouppurse.repo;

import de.grouppurse.domain.BuchungPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BuchungPositionRepository extends JpaRepository<BuchungPosition, UUID> {
    List<BuchungPosition> findByBuchungIdInOrderByPositionAsc(Collection<UUID> buchungIds);

    void deleteByBuchungId(UUID buchungId);
}

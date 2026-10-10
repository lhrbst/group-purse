package de.grouppurse.repo;

import de.grouppurse.domain.Wohngruppe;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface WohngruppeRepository extends JpaRepository<Wohngruppe, UUID> {
    Optional<Wohngruppe> findByBeitrittscode(String beitrittscode);

    boolean existsByBeitrittscode(String beitrittscode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Wohngruppe g where g.id = :id")
    Optional<Wohngruppe> findByIdForUpdate(UUID id);
}

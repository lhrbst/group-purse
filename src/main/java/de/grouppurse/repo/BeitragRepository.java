package de.grouppurse.repo;

import de.grouppurse.domain.Beitrag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BeitragRepository extends JpaRepository<Beitrag, UUID> {
    @Query("select coalesce(sum(b.betrag), 0) from Beitrag b where b.storniert = false")
    BigDecimal sumAll();

    @Query("select coalesce(sum(b.betrag), 0) from Beitrag b where b.storniert = false and b.eingangsdatum >= :von and b.eingangsdatum <= :bis")
    BigDecimal sumBetween(LocalDate von, LocalDate bis);

    List<Beitrag> findByStorniertFalse();

    List<Beitrag> findByFamilieIdOrderByEingangsdatumDesc(UUID familieId);

    List<Beitrag> findAllByOrderByEingangsdatumDesc();
}

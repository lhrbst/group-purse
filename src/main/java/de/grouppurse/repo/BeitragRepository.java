package de.grouppurse.repo;

import de.grouppurse.domain.Beitrag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BeitragRepository extends JpaRepository<Beitrag, UUID> {
    @Query("select coalesce(sum(b.betrag), 0) from Beitrag b")
    BigDecimal sumAll();

    List<Beitrag> findByFamilieIdOrderByEingangsdatumAsc(UUID familieId);

    List<Beitrag> findAllByOrderByEingangsdatumAsc();
}

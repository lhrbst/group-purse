package de.grouppurse.repo;

import de.grouppurse.domain.Art;
import de.grouppurse.domain.Buchung;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Alle Abfragen laufen über Hibernate und werden damit automatisch auf die aktive Wohngruppe gefiltert. */
public interface BuchungRepository extends JpaRepository<Buchung, UUID>, JpaSpecificationExecutor<Buchung> {
    @Query("select coalesce(sum(b.betrag), 0) from Buchung b where b.konto = :konto")
    BigDecimal sumByKonto(Konto konto);

    @Query("select coalesce(max(b.nummer), 0) from Buchung b")
    long maxNummer();

    boolean existsByKonto(Konto konto);

    boolean existsByKontoAndArtAndStatusNot(Konto konto, Art art, BuchungStatus status);

    List<Buchung> findByGruppeId(UUID gruppeId);

    long countByStatus(BuchungStatus status);

    List<Buchung> findByStatusOrderByNummerAsc(BuchungStatus status);

    List<Buchung> findByErfasstVonAndArtAndErfasstAmAfterOrderByNummerDesc(UUID erfasstVon, Art art, Instant after);

    @Query("select b from Buchung b where b.datum >= :von and b.datum <= :bis order by b.nummer desc")
    List<Buchung> search(LocalDate von, LocalDate bis);

    @Query("select b from Buchung b where b.konto = :konto and b.datum >= :von and b.datum <= :bis order by b.nummer desc")
    List<Buchung> searchByKonto(Konto konto, LocalDate von, LocalDate bis);
}

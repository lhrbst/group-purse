package de.grouppurse.repo;

import de.grouppurse.domain.Mitgliedschaft;
import de.grouppurse.domain.MitgliedStatus;
import de.grouppurse.domain.Rolle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MitgliedschaftRepository extends JpaRepository<Mitgliedschaft, UUID> {
    List<Mitgliedschaft> findByPersonId(UUID personId);

    List<Mitgliedschaft> findByWohngruppeId(UUID wohngruppeId);

    Optional<Mitgliedschaft> findByPersonIdAndWohngruppeId(UUID personId, UUID wohngruppeId);

    List<Mitgliedschaft> findByWohngruppeIdAndRolleAndStatus(UUID wohngruppeId, Rolle rolle, MitgliedStatus status);
}

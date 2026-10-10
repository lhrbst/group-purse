package de.grouppurse.repo;

import de.grouppurse.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    List<PushSubscription> findByPersonId(UUID personId);

    Optional<PushSubscription> findByPersonIdAndEndpoint(UUID personId, String endpoint);
}

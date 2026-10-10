package de.grouppurse.repo;

import de.grouppurse.domain.PasswortResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswortResetTokenRepository extends JpaRepository<PasswortResetToken, UUID> {
    Optional<PasswortResetToken> findByTokenHash(String tokenHash);
}

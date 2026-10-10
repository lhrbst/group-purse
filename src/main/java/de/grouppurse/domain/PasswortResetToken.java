package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
public class PasswortResetToken extends BaseEntity {
    public String tokenHash;
    public UUID personId;
    public Instant gueltigBis;
    public Instant eingeloestAm;
}

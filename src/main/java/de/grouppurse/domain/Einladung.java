package de.grouppurse.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.UUID;

@Entity
public class Einladung extends BaseEntity {
    public String token;
    public String email;
    public UUID wohngruppeId;
    @Enumerated(EnumType.STRING)
    public Rolle rolle;
    public Instant gueltigBis;
    public Instant eingeloestAm;
}

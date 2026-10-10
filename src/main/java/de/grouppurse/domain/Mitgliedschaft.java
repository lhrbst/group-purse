package de.grouppurse.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.UUID;

@Entity
public class Mitgliedschaft extends BaseEntity {
    public UUID personId;
    public UUID wohngruppeId;
    @Enumerated(EnumType.STRING)
    public Rolle rolle;
    @Enumerated(EnumType.STRING)
    public MitgliedStatus status;
    public UUID freigeschaltetVon;
    public Instant freigeschaltetAm;
}

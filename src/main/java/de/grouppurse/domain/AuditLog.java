package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
public class AuditLog extends TenantEntity {
    public UUID wer;
    public Instant wann = Instant.now();
    public String was;
    public String entitaet;
    public UUID entitaetId;
    public String alt;
    public String neu;
}

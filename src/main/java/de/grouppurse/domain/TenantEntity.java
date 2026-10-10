package de.grouppurse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/** Alle Daten einer Wohngruppe; Hibernate filtert und befüllt wohngruppe_id automatisch. */
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {
    @TenantId
    @Column(name = "wohngruppe_id", nullable = false, updatable = false)
    public UUID wohngruppeId;
}

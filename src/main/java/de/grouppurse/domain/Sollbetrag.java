package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
public class Sollbetrag extends TenantEntity {
    public UUID familieId;
    public BigDecimal betrag;
    public LocalDate gueltigAb;
}

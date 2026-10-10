package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.math.BigDecimal;
import java.util.UUID;

/** Eine Zeile eines Belegs: Betrag (positiv) und Kategorie. Die Summe der Positionen ist der Betrag der Ausgabe. */
@Entity
public class BuchungPosition extends TenantEntity {
    public UUID buchungId;
    public UUID kategorieId;
    public BigDecimal betrag;
    public int position;
}

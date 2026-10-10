package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Wohngruppe extends BaseEntity {
    public String name;
    public String beitrittscode;
    public BigDecimal mindestbetragKasse = new BigDecimal("50.00");
    public boolean warnungAktiv;
    public Instant letzteBestandserfassung;
}

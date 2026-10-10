package de.grouppurse.domain;

import jakarta.persistence.Entity;

@Entity
public class Familie extends TenantEntity {
    public String name;
    public String bewohnerKuerzel;
    public String kontaktEmail;
    public int faelligkeitstag = 1;
    public boolean aktiv = true;
}

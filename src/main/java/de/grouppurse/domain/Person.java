package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.time.Instant;

@Entity
public class Person extends BaseEntity {
    public String name;
    public String email;
    public String passwortHash;
    public boolean istAdmin;
    public Instant datenschutzZugestimmtAm;
}

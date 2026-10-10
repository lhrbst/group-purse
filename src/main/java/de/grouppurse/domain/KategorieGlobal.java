package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.util.UUID;

@Entity
public class KategorieGlobal extends BaseEntity {
    public String name;
    /** Oberkategorie, leer bei Hauptkategorien. */
    public UUID parentId;
}

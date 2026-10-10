package de.grouppurse.domain;

import jakarta.persistence.Entity;

import java.util.UUID;

/** Eigene Kategorie (globalRef == null) oder Ausblende-Eintrag für eine globale (globalRef gesetzt). */
@Entity
public class KategorieGruppe extends TenantEntity {
    public String name;
    public UUID globalRef;
    public boolean ausgeblendet;
    /** Oberkategorie einer eigenen Unterkategorie (global oder eigen), leer bei Hauptkategorien. */
    public UUID parentId;
}

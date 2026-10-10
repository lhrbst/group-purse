package de.grouppurse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Zentrales Kassenbuch. betrag ist vorzeichenbehaftet aus Sicht des Kontos. */
@Entity
public class Buchung extends TenantEntity {
    public long nummer;
    @Enumerated(EnumType.STRING)
    public Konto konto;
    @Enumerated(EnumType.STRING)
    public Art art;
    public BigDecimal betrag;
    public LocalDate datum;
    public UUID kategorieId;
    public String zweck;
    public String haendler;
    public String bewohnerKuerzel;
    @Enumerated(EnumType.STRING)
    public BuchungStatus status;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    public List<String> belegFotos = new ArrayList<>();
    public String begruendungOhneBeleg;
    public String ablehnungsKommentar;
    public UUID stornoVon;
    public UUID gruppeId;
    public UUID erfasstVon;
    public Instant erfasstAm = Instant.now();
    public UUID geprueftVon;
    public Instant geprueftAm;
}

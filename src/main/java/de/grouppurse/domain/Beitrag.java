package de.grouppurse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
public class Beitrag extends TenantEntity {
    public UUID familieId;
    public BigDecimal betrag;
    public LocalDate eingangsdatum;
    /** Monate als yyyy-MM */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    public List<String> monate = new ArrayList<>();
    public String notiz;
    public UUID gebuchtVon;
}

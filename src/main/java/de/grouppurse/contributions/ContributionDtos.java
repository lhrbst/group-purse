package de.grouppurse.contributions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ContributionDtos {
    private ContributionDtos() {
    }

    public enum CellStatus {BEZAHLT, TEILWEISE, OFFEN, UEBERFAELLIG, KEIN_SOLL}

    public record FamilyRequest(@NotBlank String name, String bewohnerKuerzel, String kontaktEmail, Integer faelligkeitstag,
                                BigDecimal sollbetrag, LocalDate gueltigAb, Boolean aktiv) {
    }

    public record FamilyDto(UUID id, String name, String bewohnerKuerzel, String kontaktEmail, int faelligkeitstag,
                            boolean aktiv, BigDecimal aktuellerSollbetrag) {
    }

    public record SollbetragRequest(@NotNull BigDecimal betrag, @NotNull LocalDate gueltigAb) {
    }

    public record SollbetragDto(BigDecimal betrag, LocalDate gueltigAb) {
    }

    public record ContributionRequest(@NotNull UUID familieId, @NotNull BigDecimal betrag, LocalDate eingangsdatum,
                                      List<String> monate, String notiz) {
    }

    public record ContributionDto(UUID id, UUID familieId, String familieName, BigDecimal betrag, LocalDate eingangsdatum,
                                  List<String> monate, String notiz, boolean storniert, String stornoBegruendung) {
    }

    public record CancelRequest(String begruendung) {
    }

    public record Cell(String monat, BigDecimal soll, BigDecimal bezahlt, CellStatus status) {
    }

    public record GridRow(UUID familieId, String name, String bewohnerKuerzel, boolean aktiv, int faelligkeitstag,
                          List<Cell> cells, BigDecimal guthaben, int ueberfaelligeMonate) {
    }

    public record GridDto(List<String> monate, List<GridRow> zeilen) {
    }

    public record Summary(int familien, int ueberfaellig, int offen) {
    }
}

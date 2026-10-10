package de.grouppurse.ledger;

import de.grouppurse.domain.Art;
import de.grouppurse.domain.BuchungStatus;
import de.grouppurse.domain.Konto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BuchungDto(UUID id, long nummer, Konto konto, Art art, BigDecimal betrag, LocalDate datum,
                         UUID kategorieId, String zweck, String haendler, String bewohnerKuerzel, BuchungStatus status,
                         int belegAnzahl, boolean ohneBeleg, String begruendungOhneBeleg, String ablehnungsKommentar,
                         UUID stornoVon, UUID gruppeId, UUID erfasstVon, String erfasstVonName, Instant erfasstAm,
                         UUID geprueftVon, Instant geprueftAm, List<PositionDto> positionen, String verarbeitungFehler) {
}

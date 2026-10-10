package de.grouppurse.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionDto(UUID kategorieId, BigDecimal betrag) {
}

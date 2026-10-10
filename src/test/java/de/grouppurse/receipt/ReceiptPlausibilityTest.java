package de.grouppurse.receipt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptPlausibilityTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    static final BigDecimal CASH = new BigDecimal("100.00");

    ReceiptSuggestion ok() {
        return new ReceiptSuggestion("REWE", TODAY.minusDays(1), new BigDecimal("23.40"), UUID.randomUUID());
    }

    @Test
    void validSuggestionIsPlausible() {
        assertThat(ReceiptPlausibility.isPlausible(ok(), TODAY, CASH)).isTrue();
    }

    @Test
    void futureDateIsNot() {
        var s = new ReceiptSuggestion("REWE", TODAY.plusDays(1), new BigDecimal("5"), UUID.randomUUID());
        assertThat(ReceiptPlausibility.isPlausible(s, TODAY, CASH)).isFalse();
    }

    @Test
    void zeroOrNegativeAmountIsNot() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, BigDecimal.ZERO, UUID.randomUUID()), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, new BigDecimal("-3"), UUID.randomUUID()), TODAY, CASH)).isFalse();
    }

    @Test
    void amountNotBelowCashBalanceIsNot() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, new BigDecimal("100.00"), UUID.randomUUID()), TODAY, CASH)).isFalse();
    }

    @Test
    void missingFieldsAreNot() {
        UUID c = UUID.randomUUID();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion(null, TODAY, BigDecimal.ONE, c), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", null, BigDecimal.ONE, c), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, null, c), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, BigDecimal.ONE, null), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(null, TODAY, CASH)).isFalse();
    }
}

package de.grouppurse.receipt;

import de.grouppurse.receipt.ReceiptSuggestion.PositionSuggestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptPlausibilityTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    static final BigDecimal CASH = new BigDecimal("100.00");

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    static PositionSuggestion pos(String amount) {
        return new PositionSuggestion(UUID.randomUUID(), bd(amount));
    }

    ReceiptSuggestion ok() {
        return new ReceiptSuggestion("REWE", TODAY.minusDays(1), bd("23.40"), List.of(pos("20.00"), pos("3.40")));
    }

    @Test
    void validSuggestionIsPlausible() {
        assertThat(ReceiptPlausibility.isPlausible(ok(), TODAY, CASH)).isTrue();
    }

    @Test
    void positionsMustAddUpToTheTotal() {
        var s = new ReceiptSuggestion("REWE", TODAY, bd("23.40"), List.of(pos("20.00"), pos("3.00")));
        assertThat(ReceiptPlausibility.isPlausible(s, TODAY, CASH)).isFalse();
        var withinCent = new ReceiptSuggestion("REWE", TODAY, bd("23.40"), List.of(pos("20.00"), pos("3.39")));
        assertThat(ReceiptPlausibility.isPlausible(withinCent, TODAY, CASH)).isTrue();
    }

    @Test
    void everyPositionNeedsAKnownCategoryAndPositiveAmount() {
        var unknown = new ReceiptSuggestion("REWE", TODAY, bd("10.00"), List.of(new PositionSuggestion(null, bd("10.00"))));
        assertThat(ReceiptPlausibility.isPlausible(unknown, TODAY, CASH)).isFalse();
        var negative = new ReceiptSuggestion("REWE", TODAY, bd("5.00"), List.of(pos("10.00"), pos("-5.00")));
        assertThat(ReceiptPlausibility.isPlausible(negative, TODAY, CASH)).isFalse();
    }

    @Test
    void noPositionsIsNotPlausible() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("REWE", TODAY, bd("5"), List.of()), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("REWE", TODAY, bd("5"), null), TODAY, CASH)).isFalse();
    }

    @Test
    void futureDateIsNot() {
        var s = new ReceiptSuggestion("REWE", TODAY.plusDays(1), bd("5"), List.of(pos("5")));
        assertThat(ReceiptPlausibility.isPlausible(s, TODAY, CASH)).isFalse();
    }

    @Test
    void zeroOrNegativeTotalIsNot() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, BigDecimal.ZERO, List.of(pos("1"))), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, bd("-3"), List.of(pos("1"))), TODAY, CASH)).isFalse();
    }

    @Test
    void totalNotBelowCashBalanceIsNot() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, bd("100.00"), List.of(pos("100.00"))), TODAY, CASH)).isFalse();
    }

    @Test
    void missingFieldsAreNot() {
        var p = List.of(pos("1"));
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion(null, TODAY, BigDecimal.ONE, p), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", null, BigDecimal.ONE, p), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, null, p), TODAY, CASH)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(null, TODAY, CASH)).isFalse();
    }
}

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
        assertThat(ReceiptPlausibility.isPlausible(ok(), TODAY)).isTrue();
    }

    @Test
    void positionsMustAddUpToTheTotal() {
        var s = new ReceiptSuggestion("REWE", TODAY, bd("23.40"), List.of(pos("20.00"), pos("3.00")));
        assertThat(ReceiptPlausibility.isPlausible(s, TODAY)).isFalse();
        var withinCent = new ReceiptSuggestion("REWE", TODAY, bd("23.40"), List.of(pos("20.00"), pos("3.39")));
        assertThat(ReceiptPlausibility.isPlausible(withinCent, TODAY)).isTrue();
    }

    @Test
    void everyPositionNeedsAKnownCategoryAndPositiveAmount() {
        var unknown = new ReceiptSuggestion("REWE", TODAY, bd("10.00"), List.of(new PositionSuggestion(null, bd("10.00"))));
        assertThat(ReceiptPlausibility.isPlausible(unknown, TODAY)).isFalse();
        var negative = new ReceiptSuggestion("REWE", TODAY, bd("5.00"), List.of(pos("10.00"), pos("-5.00")));
        assertThat(ReceiptPlausibility.isPlausible(negative, TODAY)).isFalse();
    }

    @Test
    void noPositionsIsNotPlausible() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("REWE", TODAY, bd("5"), List.of()), TODAY)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("REWE", TODAY, bd("5"), null), TODAY)).isFalse();
    }

    @Test
    void futureDateIsNot() {
        var s = new ReceiptSuggestion("REWE", TODAY.plusDays(1), bd("5"), List.of(pos("5")));
        assertThat(ReceiptPlausibility.isPlausible(s, TODAY)).isFalse();
    }

    @Test
    void zeroOrNegativeTotalIsNot() {
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, BigDecimal.ZERO, List.of(pos("1"))), TODAY)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, bd("-3"), List.of(pos("1"))), TODAY)).isFalse();
    }

    @Test
    void missingFieldsAreNot() {
        var p = List.of(pos("1"));
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion(null, TODAY, BigDecimal.ONE, p), TODAY)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", null, BigDecimal.ONE, p), TODAY)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(new ReceiptSuggestion("R", TODAY, null, p), TODAY)).isFalse();
        assertThat(ReceiptPlausibility.isPlausible(null, TODAY)).isFalse();
    }
}

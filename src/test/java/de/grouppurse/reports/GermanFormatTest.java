package de.grouppurse.reports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class GermanFormatTest {
    @Test
    void formatsMoneyWithGermanSeparators() {
        assertThat(GermanFormat.money(new BigDecimal("1234.5"))).isEqualTo("1.234,50 €");
        assertThat(GermanFormat.money(new BigDecimal("0"))).isEqualTo("0,00 €");
        assertThat(GermanFormat.money(new BigDecimal("-7.005"))).isEqualTo("-7,01 €");
    }

    @Test
    void signedValuesCarryAnExplicitPlus() {
        assertThat(GermanFormat.signedMoney(new BigDecimal("93"))).isEqualTo("+93,00 €");
        assertThat(GermanFormat.signedMoney(new BigDecimal("-93"))).isEqualTo("-93,00 €");
        assertThat(GermanFormat.signedMoney(BigDecimal.ZERO)).isEqualTo("0,00 €");
        assertThat(GermanFormat.signedPercent(new BigDecimal("1328.6"))).isEqualTo("+1.328,6 %");
        assertThat(GermanFormat.percent(new BigDecimal("46.2"))).isEqualTo("46,2 %");
    }

    @Test
    void formatsDatesAndMonthNamesInGerman() {
        assertThat(GermanFormat.date(LocalDate.of(2026, 3, 4))).isEqualTo("04.03.2026");
        assertThat(GermanFormat.month(YearMonth.of(2026, 3))).isEqualTo("März 2026");
        assertThat(GermanFormat.month(YearMonth.of(2026, 10))).isEqualTo("Oktober 2026");
    }
}

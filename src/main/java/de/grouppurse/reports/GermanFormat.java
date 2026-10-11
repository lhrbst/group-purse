package de.grouppurse.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/** Deutsche Darstellung von Beträgen, Prozenten, Daten und Monaten für Bericht und PDF. */
final class GermanFormat {
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.GERMANY);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private GermanFormat() {
    }

    static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.00", SYMBOLS).format(value.setScale(2, RoundingMode.HALF_UP)) + " €";
    }

    static String signedMoney(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + money(value);
    }

    static String percent(BigDecimal value) {
        return new DecimalFormat("#,##0.0", SYMBOLS).format(value) + " %";
    }

    static String signedPercent(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + percent(value);
    }

    static String date(LocalDate date) {
        return DATE.format(date);
    }

    static String month(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.GERMAN) + " " + month.getYear();
    }
}

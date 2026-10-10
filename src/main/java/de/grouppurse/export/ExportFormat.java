package de.grouppurse.export;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ExportFormat {
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private ExportFormat() {
    }

    private static DecimalFormat plain() {
        return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }

    private static DecimalFormat grouped() {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }

    /** 12,50 ohne Tausenderpunkt, für Excel. */
    static String number(BigDecimal v) {
        return plain().format(v);
    }

    /** 1.234,50 € für die Anzeige. */
    static String euro(BigDecimal v) {
        return grouped().format(v) + " €";
    }

    static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    static String dateTime(Instant i) {
        return i == null ? "" : DATETIME.format(i.atZone(ZONE));
    }
}

package de.grouppurse.domain;

public enum BuchungStatus {
    GEBUCHT,
    /** Beleg ist gelesen (oder vom Kassenverwalter erfasst) und wartet auf die Prüfung. */
    EINGEREICHT,
    GEPRUEFT,
    ABGELEHNT,
    STORNIERT,
    /** Nur das Foto liegt vor; die KI liest den Beleg im Hintergrund. Betrag ist noch 0. */
    VERARBEITUNG,
    /** Die KI konnte den Beleg nicht lesen oder ist aus: der Kassenverwalter erfasst die Daten selbst. */
    MANUELL
}

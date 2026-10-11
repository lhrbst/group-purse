# Monatsübersicht und PDF-Monatsbericht für den Kassenverwalter

Stand: 11.10.2026. Ergänzt die Spezifikation „Auswertungen und Export“. Hebt die Zurückstellung des PDF-Exports vom 10.10.2026 teilweise auf.

## Ziel

Der Kassenverwalter sieht die Ausgaben eines Monats als lesbaren Text und als Tabellen, nicht nur als Diagramm. Für denselben Monat kann er von Hand ein PDF erzeugen und herunterladen, das dieselbe Übersicht enthält und sich für die Kassenprüfung eignet.

Nicht im Umfang: CSV-Export, ZIP der Belegfotos, Jahresabschluss (bleiben zurückgestellt), Einnahmen und Kassenstände im Bericht, Versand per E-Mail, gespeicherte Berichte.

## Entscheidungen

- **Eine Quelle für Bildschirm und PDF.** Ein neuer `MonthlyReportService` berechnet den Monatsbericht einmal. Das JSON für die Textansicht und das PDF entstehen aus denselben Daten, die Zahlen können nicht auseinanderlaufen. Das PDF wird mit OpenPDF im Backend erzeugt (wie in den technischen Festlegungen vorgesehen), nicht im Frontend.
- **Umfang (Variante B):** Zusammenfassung, Aufschlüsselung nach Kategorie und Einzelliste aller Ausgaben des Monats.
- **Nichts wird gespeichert.** Das PDF wird bei jedem Aufruf neu erzeugt.

## Zahlenbasis

- Berücksichtigt werden Barkassen- und Kontoausgaben (`Art` AUSGABE und KONTOAUSGABE) mit Belegdatum im gewählten Monat, gerechnet aus den Belegpositionen.
- Ausgeschlossen: Belege mit Status `STORNIERT`, `ABGELEHNT`, `VERARBEITUNG` und `MANUELL`. Belege ohne feststehenden Betrag werden im Bericht **nicht erwähnt**.
- Belege mit Status `EINGEREICHT` zählen mit und sind in der Einzelliste als „zu prüfen“ markiert. Der Kopf nennt ihre Anzahl und Summe.
- Beträge werden als positive Ausgaben dargestellt.

## Inhalt

**Kopf**
- Wohngruppe, Monat, Erstellungsdatum.
- Summe der Ausgaben, Anzahl der Belege, Vergleich mit dem Vormonat (absolute und prozentuale Änderung; ohne Vormonatsausgaben nur der absolute Wert).
- Anzahl und Summe der noch nicht geprüften Belege, falls vorhanden.
- Fließtext zu den größten Posten, z. B. „Die größten Posten waren Lebensmittel (412,30 €, 46 %) und Haushalt (180,00 €, 20 %).“ (bis zu drei Kategorien).

**Nach Kategorie**
Tabelle Hauptkategorie, Unterkategorie, Betrag, Anteil in Prozent, absteigend nach Betrag, mit Summenzeile. Die Anteile werden so gerundet, dass sie sich auf die Gesamtsumme beziehen; die Summenzeile zeigt den exakten Betrag.

**Einzelliste**
Datum, Nr., Konto, Händler, Kategorie(n), Erfasst von, Betrag, aufsteigend nach Datum und Nummer. Bei Belegen mit mehreren Kategorien steht der Gesamtbetrag in der Zeile, die Aufteilung darunter eingerückt. Ungeprüfte Belege tragen die Markierung „zu prüfen“.

**Leerer Monat:** „Keine Ausgaben in diesem Monat.“ Der Bericht wird trotzdem erzeugt.

## Schnittstellen

- `GET /api/reports/monthly?monat=YYYY-MM` liefert den Monatsbericht als JSON.
- `GET /api/reports/monthly.pdf?monat=YYYY-MM` liefert das PDF mit `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="Gruppenkasse-YYYY-MM.pdf"` und `Cache-Control: no-store`.
- Nur der Kassenverwalter der aktiven Gruppe (`access.requireKassenverwalter()`), andere bekommen 403. Alle Daten sind wie überall auf die Gruppe beschränkt.
- Ungültiges Format oder ein Monat in der Zukunft ergibt 400. Der laufende Monat ist erlaubt.

## Oberfläche

- Auf der Seite *Auswertungen* kommt oben eine Karte „Monatsübersicht“, unabhängig von den Filtern darunter.
- Monatsauswahl (Standard: laufender Monat) mit „Vormonat“ und „Nächster Monat“. „Nächster Monat“ ist im laufenden Monat deaktiviert.
- Darunter Kopf mit Fließtext, Kategorietabelle und Einzelliste. Auf dem Handy scrollen die Tabellen horizontal.
- Der Button „PDF herunterladen“ ist ein gewöhnlicher Link auf den PDF-Endpunkt. Die Sitzung läuft über das Cookie, deshalb funktioniert der Download auch auf dem iPhone ohne Blob-Umweg.

## PDF-Format

DIN A4 hoch, deutsche Formatierung (`1.234,56 €`, `TT.MM.JJJJ`), Seitenzahlen, wiederholte Tabellenköpfe auf Folgeseiten. Fußzeile: „Erstellt am … von …“ mit dem Namen des Kassenverwalters. Schrift: eingebaute Standardschrift (Helvetica); Zeichen außerhalb des Latin-1-Bereichs in Händlernamen werden durch „?“ ersetzt, damit die Erzeugung nie daran scheitert.

## Betrieb und Datenschutz

- Metrik `gk_report_pdf_total` mit Label `outcome` (ok, error), ohne Beträge und ohne Gruppenbezug, passend zur Festlegung für das Monitoring.
- Kein Audit-Eintrag, weil nichts verändert wird.
- Das PDF enthält Namen der Erfasser. Es verlässt die App nur durch den Download des Kassenverwalters; die App verschickt nichts.

## Tests

- **Backend (Service):** Summen, Anteile und Rundung, Vormonatsvergleich, Ausschluss von STORNIERT, ABGELEHNT, VERARBEITUNG und MANUELL, Markierung ungeprüfter Belege, Aufteilung auf mehrere Kategorien (Haupt- und Unterkategorie), Barkasse und Konto zusammen, leerer Monat, Monatsgrenzen (erster und letzter Tag).
- **Backend (Web):** Rechte (Teammitglied 403), Gruppenisolation, ungültiger und zukünftiger Monat (400), Antwort-Header.
- **PDF:** Antwort beginnt mit `%PDF`, der extrahierte Text enthält Summe, Kategorien, Händler und Markierung; ein Monat mit über 100 Belegen prüft den Seitenumbruch und die Seitenzahl; Sonderzeichen im Händlernamen brechen die Erzeugung nicht ab.
- **Frontend:** Anzeige und Formatierung der Monatsübersicht, Monatswechsel, leerer Monat.
- **E2E:** Der Kassenverwalter wählt einen Monat, sieht die Tabellen und lädt das PDF herunter (`%PDF`, Dateiname); ein Teammitglied bekommt keinen Zugriff.

## Änderung an der Spezifikation

Im Abschnitt „Zurückgestellt“ wird der Export auf CSV, Belegfoto-ZIP und Jahresabschluss eingegrenzt; der PDF-Monatsbericht kommt als eigener Abschnitt unter „Auswertungen und Export“ dazu.

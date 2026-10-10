package de.grouppurse.receipt;

import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Konto;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import de.grouppurse.receipt.ReceiptSuggestion.PositionSuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiptRecognitionService {
    private static final Logger log = LoggerFactory.getLogger(ReceiptRecognitionService.class);

    private final ReceiptModelClient client;
    private final ReceiptAiProperties props;
    private final CategoryService categories;
    private final BalanceService balances;

    public ReceiptRecognitionService(ReceiptModelClient client, ReceiptAiProperties props, CategoryService categories, BalanceService balances) {
        this.client = client;
        this.props = props;
        this.categories = categories;
        this.balances = balances;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * Liest den Bon mit dem kleinen Modell und teilt die Zeilen auf die wählbaren Kategorien auf. Ist ein Feld leer, die Aufteilung
     * unvollständig oder die Summe der Positionen nicht gleich der Endsumme, geht derselbe Bon einmal an das stärkere Modell.
     * Leer, wenn die Erkennung aus ist oder beide Aufrufe scheitern.
     */
    public Optional<ReceiptSuggestion> recognize(byte[] image, String mimeType) {
        if (!props.enabled()) {
            return Optional.empty();
        }
        List<CategoryDto> leaves = categories.leaves();
        List<String> names = leaves.stream().map(CategoryDto::name).toList();
        var kassenstand = balances.balance(Konto.BARKASSE);
        LocalDate today = LocalDate.now();

        Optional<ReceiptSuggestion> first = attempt(image, mimeType, names, leaves, props.model());
        if (first.isPresent() && ReceiptPlausibility.isPlausible(first.get(), today, kassenstand)) {
            return first;
        }
        Optional<ReceiptSuggestion> second = attempt(image, mimeType, names, leaves, props.fallbackModel());
        if (second.isPresent() && ReceiptPlausibility.isPlausible(second.get(), today, kassenstand)) {
            return second;
        }
        return second.isPresent() ? second : first;
    }

    private Optional<ReceiptSuggestion> attempt(byte[] image, String mimeType, List<String> names, List<CategoryDto> leaves, String model) {
        try {
            return client.extract(image, mimeType, names, model).map(raw -> map(raw, leaves));
        } catch (RuntimeException e) {
            log.warn("Belegerkennung mit {} fehlgeschlagen: {}", model, e.toString());
            return Optional.empty();
        }
    }

    static ReceiptSuggestion map(RawReceipt raw, List<CategoryDto> leaves) {
        LocalDate date = null;
        if (raw.datum() != null) {
            try {
                date = LocalDate.parse(raw.datum().trim());
            } catch (RuntimeException ignored) {
                // unlesbares Datum bleibt leer
            }
        }
        // gleiche Kategorien zusammenfassen; unbekannte Kategorien bleiben als Position ohne Kategorie stehen
        Map<UUID, BigDecimal> known = new LinkedHashMap<>();
        List<PositionSuggestion> unknown = new ArrayList<>();
        if (raw.positionen() != null) {
            for (var p : raw.positionen()) {
                if (p == null || p.betrag() == null) {
                    continue;
                }
                UUID id = findCategory(p.kategorie(), leaves);
                if (id == null) {
                    unknown.add(new PositionSuggestion(null, p.betrag()));
                } else {
                    known.merge(id, p.betrag(), BigDecimal::add);
                }
            }
        }
        List<PositionSuggestion> positions = new ArrayList<>();
        known.forEach((k, v) -> positions.add(new PositionSuggestion(k, v)));
        positions.addAll(unknown);
        String merchant = raw.haendler() == null || raw.haendler().isBlank() ? null : raw.haendler().trim();
        return new ReceiptSuggestion(merchant, date, raw.gesamtbetrag(), positions);
    }

    /** Findet die Kategorie über den Namen; akzeptiert auch "Lebensmittel › Obst und Gemüse" und nimmt dann den letzten Teil. */
    private static UUID findCategory(String name, List<CategoryDto> leaves) {
        if (name == null) {
            return null;
        }
        String n = name.contains("›") ? name.substring(name.lastIndexOf('›') + 1) : name;
        String wanted = n.trim();
        return leaves.stream().filter(c -> c.name().equalsIgnoreCase(wanted)).map(CategoryDto::id).findFirst().orElse(null);
    }
}

package de.grouppurse.receipt;

import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
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

    public ReceiptRecognitionService(ReceiptModelClient client, ReceiptAiProperties props, CategoryService categories) {
        this.client = client;
        this.props = props;
        this.categories = categories;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /**
     * Liest den Beleg mit dem kleinen Modell und teilt die Zeilen auf die wählbaren Kategorien auf. Ist ein Feld leer, die Aufteilung
     * unvollständig, die Summe der Positionen nicht gleich der Endsumme oder das Modell unsicher, geht derselbe Beleg einmal an das
     * stärkere Modell. Liefert nur ein plausibles Ergebnis, sonst leer (Beleg wird dann wiederholt bzw. manuell erfasst).
     *
     * @throws ReceiptUncertainException wenn das Modell den Beleg als nicht verlässlich lesbar meldet; ein erneuter Versuch bringt dann nichts
     */
    public Optional<ReceiptSuggestion> recognize(List<ReceiptImage> images) {
        if (!props.enabled() || images.isEmpty()) {
            return Optional.empty();
        }
        List<CategoryDto> leaves = categories.leaves();
        List<String> names = leaves.stream().map(CategoryDto::name).toList();
        LocalDate today = LocalDate.now();

        Attempt first = attempt(images, names, leaves, props.model());
        if (first.usable(today)) {
            return first.suggestion();
        }
        Attempt second = attempt(images, names, leaves, props.fallbackModel());
        if (second.usable(today)) {
            return second.suggestion();
        }
        String reason = second.uncertainty() != null ? second.uncertainty() : first.uncertainty();
        if (reason != null) {
            throw new ReceiptUncertainException(reason);
        }
        return Optional.empty();
    }

    /** Ergebnis eines Modellaufrufs: Vorschlag (kann fehlen) und ggf. die Unsicherheit, die das Modell gemeldet hat. */
    private record Attempt(Optional<ReceiptSuggestion> suggestion, String uncertainty) {
        boolean usable(LocalDate today) {
            return uncertainty == null && suggestion.isPresent() && ReceiptPlausibility.isPlausible(suggestion.get(), today);
        }
    }

    private Attempt attempt(List<ReceiptImage> images, List<String> names, List<CategoryDto> leaves, String model) {
        try {
            Optional<RawReceipt> raw = client.extract(images, names, model);
            if (raw.isPresent() && raw.get().isUncertain()) {
                String why = raw.get().unsicherheit();
                return new Attempt(Optional.empty(), why == null || why.isBlank() ? "kein Grund genannt" : why.trim());
            }
            return new Attempt(raw.map(r -> map(r, leaves)), null);
        } catch (RuntimeException e) {
            log.warn("Belegerkennung mit {} fehlgeschlagen: {}", model, e.toString());
            return new Attempt(Optional.empty(), null);
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

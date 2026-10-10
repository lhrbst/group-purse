package de.grouppurse.receipt;

import de.grouppurse.category.CategoryDto;
import de.grouppurse.category.CategoryService;
import de.grouppurse.domain.Konto;
import de.grouppurse.ledger.BalanceService;
import de.grouppurse.receipt.ReceiptModelClient.RawReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
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
     * Liest den Bon mit dem kleinen Modell; ist ein Feld leer oder unplausibel, geht derselbe Bon einmal an das
     * stärkere Modell. Leer, wenn die Erkennung aus ist oder beide Aufrufe scheitern.
     */
    public Optional<ReceiptSuggestion> recognize(byte[] image, String mimeType) {
        if (!props.enabled()) {
            return Optional.empty();
        }
        List<CategoryDto> visible = categories.visible();
        List<String> names = visible.stream().map(CategoryDto::name).toList();
        var kassenstand = balances.balance(Konto.BARKASSE);
        LocalDate today = LocalDate.now();

        Optional<ReceiptSuggestion> first = attempt(image, mimeType, names, visible, props.model());
        if (first.isPresent() && ReceiptPlausibility.isPlausible(first.get(), today, kassenstand)) {
            return first;
        }
        Optional<ReceiptSuggestion> second = attempt(image, mimeType, names, visible, props.fallbackModel());
        if (second.isPresent() && ReceiptPlausibility.isPlausible(second.get(), today, kassenstand)) {
            return second;
        }
        return second.isPresent() ? second : first;
    }

    private Optional<ReceiptSuggestion> attempt(byte[] image, String mimeType, List<String> names, List<CategoryDto> visible, String model) {
        try {
            return client.extract(image, mimeType, names, model).map(raw -> map(raw, visible));
        } catch (RuntimeException e) {
            log.warn("Belegerkennung mit {} fehlgeschlagen: {}", model, e.toString());
            return Optional.empty();
        }
    }

    static ReceiptSuggestion map(RawReceipt raw, List<CategoryDto> visible) {
        LocalDate date = null;
        if (raw.datum() != null) {
            try {
                date = LocalDate.parse(raw.datum().trim());
            } catch (RuntimeException ignored) {
                // unlesbares Datum bleibt leer
            }
        }
        UUID category = null;
        if (raw.kategorie() != null) {
            category = visible.stream().filter(c -> c.name().equalsIgnoreCase(raw.kategorie().trim()))
                    .map(CategoryDto::id).findFirst().orElse(null);
        }
        String merchant = raw.haendler() == null || raw.haendler().isBlank() ? null : raw.haendler().trim();
        return new ReceiptSuggestion(merchant, date, raw.gesamtbetrag(), category);
    }
}

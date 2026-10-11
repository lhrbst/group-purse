package de.grouppurse.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fachliche Metriken der Gruppenkasse. Nur Zähler und Zeiten mit endlich vielen Tag-Werten: keine Beträge,
 * keine Personen, keine Wohngruppen.
 */
@Component
public class GkMetrics {
    private final MeterRegistry registry;

    public GkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void booking(String type) {
        registry.counter("gk.ledger.bookings", "type", type).increment();
    }

    /** from ist NEU bei der Erstanlage eines Belegs. */
    public void expenseTransition(String from, String to) {
        registry.counter("gk.expense.transitions", "from", from, "to", to).increment();
    }

    /** success, failure oder locked. */
    public void login(String outcome) {
        registry.counter("gk.login", "outcome", outcome).increment();
    }

    /** success oder rejected. */
    public void registration(String outcome) {
        registry.counter("gk.registrations", "outcome", outcome).increment();
    }

    /** success oder rejected. */
    public void invitation(String outcome) {
        registry.counter("gk.invitations", "outcome", outcome).increment();
    }

    /** too_large oder bad_type. */
    public void uploadRejected(String reason) {
        registry.counter("gk.upload.rejected", "reason", reason).increment();
    }

    /** Dauer eines Modellaufrufs; outcome ist ok, timeout oder error. */
    public void aiCall(String model, String outcome, Duration duration) {
        Timer.builder("gk.receipt.ai.duration").tag("model", model).tag("outcome", outcome).register(registry).record(duration);
    }

    /** usable, uncertain, implausible oder failed. */
    public void aiResult(String model, String result) {
        registry.counter("gk.receipt.ai.results", "model", model, "result", result).increment();
    }

    public void aiFallback() {
        registry.counter("gk.receipt.ai.fallback").increment();
    }

    /** uncertain oder attempts. */
    public void aiGaveUp(String reason) {
        registry.counter("gk.receipt.ai.gave_up", "reason", reason).increment();
    }

    /** Zeit von der Aufnahme bis zum Ergebnis; outcome ist EINGEREICHT oder MANUELL. */
    public void receiptEndToEnd(String outcome, Duration duration) {
        Timer.builder("gk.receipt.end_to_end").tag("outcome", outcome).register(registry).record(duration);
    }
}

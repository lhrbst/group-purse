package de.grouppurse.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GkMetricsTest {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    GkMetrics m = new GkMetrics(registry);

    @Test
    void countersCarryTheirTags() {
        m.booking("AUSGABE");
        m.booking("AUSGABE");
        m.login("failure");
        m.expenseTransition("VERARBEITUNG", "EINGEREICHT");
        assertThat(registry.get("gk.ledger.bookings").tag("type", "AUSGABE").counter().count()).isEqualTo(2);
        assertThat(registry.get("gk.login").tag("outcome", "failure").counter().count()).isEqualTo(1);
        assertThat(registry.get("gk.expense.transitions").tag("from", "VERARBEITUNG").tag("to", "EINGEREICHT").counter().count()).isEqualTo(1);
    }

    @Test
    void timersRecordDurationPerModelAndOutcome() {
        m.aiCall("klein", "ok", Duration.ofSeconds(12));
        m.receiptEndToEnd("EINGEREICHT", Duration.ofSeconds(40));
        var t = registry.get("gk.receipt.ai.duration").tag("model", "klein").tag("outcome", "ok").timer();
        assertThat(t.count()).isEqualTo(1);
        assertThat(t.totalTime(TimeUnit.SECONDS)).isEqualTo(12);
        assertThat(registry.get("gk.receipt.end_to_end").tag("outcome", "EINGEREICHT").timer().count()).isEqualTo(1);
    }

    @Test
    void noMetricCarriesMoneyOrNames() {
        m.booking("AUFFUELLUNG");
        m.aiResult("klein", "usable");
        m.aiFallback();
        m.aiGaveUp("attempts");
        m.uploadRejected("bad_type");
        m.registration("success");
        m.invitation("rejected");
        registry.getMeters().forEach(meter -> meter.getId().getTags()
                .forEach(tag -> assertThat(tag.getKey()).isNotIn("gruppe", "wohngruppe", "email", "name", "betrag")));
    }
}

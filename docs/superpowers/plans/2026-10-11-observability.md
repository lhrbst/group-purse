# Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. (Entscheidung des Nutzers: inline ausführen, keine Subagenten.)

**Goal:** Die Gruppenkasse technisch und fachlich überwachbar machen: Micrometer-Metriken und JSON-Logs in der App, Prometheus/Loki/Grafana-Stack auf der VM, Dashboards und Alarmregeln als Dateien im Repo.

**Architecture:** Spring Boot exponiert Prometheus-Metriken auf einem internen Management-Port 8081. Prometheus holt dort und bei Exportern (Postgres, Node, cAdvisor, Blackbox, Caddy) ab, Alloy schickt Docker-Logs nach Loki, Grafana zeigt alles (Provisioning aus Dateien). Alarmregeln sind Prometheus-Regeldateien, in Grafana sichtbar, ohne Zustellung. Fachliche Metriken sind reine Zähler/Timer an den bestehenden Stellen, keine Beträge, keine Gruppen, keine Personen.

**Tech Stack:** Spring Boot 4.1.1, Micrometer, Prometheus 3, Loki 3, Grafana 12, Grafana Alloy, postgres_exporter, node_exporter, cAdvisor, blackbox_exporter, Docker Compose, Playwright (E2E).

**Spec:** `docs/superpowers/specs/2026-10-11-observability-design.md`

## Global Constraints

- Keine Geldbeträge, Personendaten (Name, E-Mail), Gruppennamen oder Gruppen-IDs in Metrik-Labels. Keine Beträge, E-Mails, Passwörter, Tokens in Logzeilen.
- Metrik-Namen mit Präfix `gk.` (Prometheus: `gk_…`). Labels mit endlich vielen Werten.
- Management-Port 8081 wird nirgends veröffentlicht (`ports:`) und nie von Caddy durchgereicht, außer `/actuator/health*` für den Uptime-Check.
- Kein E-Mail-/Push-/Signal-Versand. Keine Export-Funktionen.
- Alle Container mit Speicherlimit. Aufbewahrung: Prometheus 30 Tage / 3 GB, Loki 14 Tage.
- Deutsche Kommentare/Texte wie im Rest des Projekts, Code und Bezeichner englisch.
- Backend-Tests: `./mvnw -q test`; E2E: `cd e2e && node scripts/build.mjs && SKIP_BUILD=1 npx playwright test`.

## Abweichungen von der Spec (bewusst)

1. **Alarmregeln als Prometheus-Regeldateien** statt Grafana-managed (kürzer, mit `promtool test rules` testbar; Grafana zeigt sie unter Alerting → Alert rules an, das Übersicht-Dashboard über die Metrik `ALERTS`).
2. **Belegerkennung:** Timer `gk.receipt.ai.duration` (Tags `model`, `outcome` = `ok|timeout|error`) misst den Modellaufruf im Client; Zähler `gk.receipt.ai.results` (Tags `model`, `result` = `usable|uncertain|implausible|failed`) im Service. Die Spec-Wertemenge „ok, uncertain, implausible, error, timeout“ verteilt sich so auf beide.

---

## Phase 1: Anwendung

### Task 1: Prometheus-Endpunkt auf Management-Port 8081

**Files:**
- Modify: `pom.xml` (Dependency `micrometer-registry-prometheus`)
- Modify: `src/main/resources/application.yml`
- Modify: `Dockerfile:31` (Healthcheck → 8081), `deploy/deploy.sh:23`, `docker-compose.yml` (lokal: kein Port nötig), `e2e/support/stack.ts`, `e2e/support/ports.ts`
- Modify: `frontend/vite.config.ts` (Proxy `/actuator` → Management-Port nicht nötig; Eintrag entfernen oder auf 8081 setzen)
- Modify: `deploy/Caddyfile`

**Interfaces:**
- Produces: `GET http://app:8081/actuator/prometheus` und `/actuator/health`; Umgebungsvariable `MANAGEMENT_PORT` (Default 8081).

- [ ] **Step 1: Dependency und Konfiguration**

`pom.xml`, neben dem Actuator-Starter:
```xml
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
```
`application.yml`, Abschnitt `management` ersetzen durch:
```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:8081}
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        gk.receipt.ai.duration: true
        gk.receipt.end_to_end: true
      minimum-expected-value:
        gk.receipt.ai.duration: 1s
        gk.receipt.end_to_end: 5s
      maximum-expected-value:
        gk.receipt.ai.duration: 300s
        gk.receipt.end_to_end: 3600s
    tags:
      application: gruppenkasse
```

- [ ] **Step 2: Health-Verweise auf 8081 umstellen**

`Dockerfile`: `wget -qO- http://localhost:8081/actuator/health`. `deploy/deploy.sh` `healthy()`: `…exec -T app wget -qO- http://localhost:8081/actuator/health…`. `e2e/support/ports.ts`: `management: Number(env.E2E_MANAGEMENT_PORT ?? 18081)` und `URLS.management`; `e2e/support/stack.ts`: Env `MANAGEMENT_PORT: String(PORTS.management)`, Health-Wait gegen `URLS.management`. `frontend/vite.config.ts`: Proxy-Eintrag `/actuator` entfernen (beide Stellen). `deploy/Caddyfile`:
```
{$DOMAIN} {
	handle /actuator/health* {
		reverse_proxy app:8081
	}
	handle /actuator/* {
		respond 404
	}
	handle {
		reverse_proxy app:8080
	}
	encode gzip
	request_body { max_size 20MB }
	header { -Server }
}
```

- [ ] **Step 3: Prüfen**

`./mvnw -q test` (muss grün bleiben), danach `./mvnw -q -DskipTests package`, Backend starten (`java -jar target/group-purse-*.jar` mit passenden Env) und prüfen:
`curl -s localhost:8081/actuator/prometheus | head` liefert `# HELP`, `curl -s -o /dev/null -w "%{http_code}" localhost:8080/actuator/prometheus` liefert 404 (SpaConfig leitet `actuator/` nicht auf index.html um; falls 200 mit HTML: Pfad in `SpaConfig` ausschließen).

- [ ] **Step 4: Commit** `feat(obs): prometheus endpoint on internal management port 8081`

### Task 2: Metrik-Fassade `GkMetrics`

**Files:**
- Create: `src/main/java/de/grouppurse/metrics/GkMetrics.java`
- Test: `src/test/java/de/grouppurse/metrics/GkMetricsTest.java`

**Interfaces:**
- Produces (alle `public`, kein Rückgabewert):
  - `void booking(String type)` → Zähler `gk.ledger.bookings`, Tag `type`
  - `void expenseTransition(String from, String to)` → `gk.expense.transitions` (`from`, `to`; `from` = `NEU` bei Erstanlage)
  - `void login(String outcome)` → `gk.login` (`success|failure|locked`)
  - `void registration(String outcome)` → `gk.registrations` (`success|rejected`)
  - `void invitation(String outcome)` → `gk.invitations` (`success|rejected`)
  - `void uploadRejected(String reason)` → `gk.upload.rejected` (`too_large|bad_type`)
  - `void aiCall(String model, String outcome, Duration d)` → Timer `gk.receipt.ai.duration`
  - `void aiResult(String model, String result)` → `gk.receipt.ai.results`
  - `void aiFallback()` → `gk.receipt.ai.fallback`
  - `void aiGaveUp(String reason)` → `gk.receipt.ai.gave_up` (`uncertain|attempts`)
  - `void receiptEndToEnd(String outcome, Duration d)` → Timer `gk.receipt.end_to_end`

- [ ] **Step 1: Failing test**

```java
package de.grouppurse.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        assertThat(t.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(12);
        assertThat(registry.get("gk.receipt.end_to_end").tag("outcome", "EINGEREICHT").timer().count()).isEqualTo(1);
    }

    @Test
    void noMetricCarriesMoneyOrNames() {
        m.booking("AUFFUELLUNG");
        registry.getMeters().forEach(meter -> meter.getId().getTags()
                .forEach(tag -> assertThat(tag.getKey()).isNotIn("gruppe", "wohngruppe", "email", "name", "betrag")));
    }
}
```

- [ ] **Step 2:** `./mvnw -q test -Dtest=GkMetricsTest` → FAIL (Klasse fehlt).

- [ ] **Step 3: Implementierung**

```java
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

    public void expenseTransition(String from, String to) {
        registry.counter("gk.expense.transitions", "from", from, "to", to).increment();
    }

    public void login(String outcome) {
        registry.counter("gk.login", "outcome", outcome).increment();
    }

    public void registration(String outcome) {
        registry.counter("gk.registrations", "outcome", outcome).increment();
    }

    public void invitation(String outcome) {
        registry.counter("gk.invitations", "outcome", outcome).increment();
    }

    public void uploadRejected(String reason) {
        registry.counter("gk.upload.rejected", "reason", reason).increment();
    }

    public void aiCall(String model, String outcome, Duration d) {
        Timer.builder("gk.receipt.ai.duration").tag("model", model).tag("outcome", outcome).register(registry).record(d);
    }

    public void aiResult(String model, String result) {
        registry.counter("gk.receipt.ai.results", "model", model, "result", result).increment();
    }

    public void aiFallback() {
        registry.counter("gk.receipt.ai.fallback").increment();
    }

    public void aiGaveUp(String reason) {
        registry.counter("gk.receipt.ai.gave_up", "reason", reason).increment();
    }

    public void receiptEndToEnd(String outcome, Duration d) {
        Timer.builder("gk.receipt.end_to_end").tag("outcome", outcome).register(registry).record(d);
    }
}
```

- [ ] **Step 4:** Test erneut → PASS. **Step 5: Commit** `feat(obs): GkMetrics facade`

### Task 3: Metriken an den bestehenden Stellen auslösen

**Files:**
- Modify: `ledger/LedgerService.java` (Konstruktor +`GkMetrics`; in `book` und `save` nach dem Speichern `metrics.booking(b.art.name())`)
- Modify: `expense/ExpenseService.java` (Konstruktor +`GkMetrics`; Hilfsmethode `setStatus(Buchung b, BuchungStatus to)` ersetzt alle `b.status = …` an den Zeilen 105, 116, 129, 163, 187, 197, 214, 238, 254, 271; `from` ist `b.status` bzw. `NEU`, wenn `b.status == null`)
- Modify: `expense/ReceiptStorage.java` (`GkMetrics` per Konstruktor; vor den beiden `IllegalArgumentException` in `store` `metrics.uploadRejected("too_large")` bzw. `("bad_type")`)
- Modify: `auth/AuthController.java` (login: `locked` bei 429, `failure` bei 401, `success` nach Start der Session; register: `success`, bei Exception `rejected` per try/catch, die Exception wird weitergeworfen)
- Modify: `admin/InvitationService.java` (`redeem`: `success` am Ende, `rejected` bei jeder der geworfenen Ausnahmen über try/finally-Muster)
- Modify: `receipt/SpringAiReceiptClient.java` (Timer: Start `System.nanoTime()`, bei Erfolg `ok`, `TimeoutException` → `timeout`, andere Ausnahme → `error`, InterruptedException → `error`)
- Modify: `receipt/ReceiptRecognitionService.java` (`GkMetrics` im Konstruktor; in `attempt` je Modell `aiResult(model, …)` mit `usable|uncertain|implausible|failed`; vor dem Aufruf des Fallback-Modells `aiFallback()`)
- Modify: `expense/ExpenseService.recordRecognitionFailure/giveUpRecognition/applyRecognition` (`receiptEndToEnd(outcome, Duration.between(b.erfasstAm, Instant.now()))` bei Übergang nach EINGEREICHT bzw. MANUELL; `aiGaveUp("attempts")` bzw. `("uncertain")`)
- Modify: Tests, die Konstruktoren direkt aufrufen: `ReceiptRecognitionServiceTest` (`new GkMetrics(new SimpleMeterRegistry())` als zusätzlicher Parameter)
- Test: `src/test/java/de/grouppurse/metrics/MetricsIntegrationTest.java`

**Interfaces:**
- Consumes: `GkMetrics` aus Task 2.

- [ ] **Step 1: Failing test** (Integrationstest auf `AbstractIntegrationTest`, `@Autowired MeterRegistry registry`, vorhandene Helfer für Anmeldung/Erfassung wie in `ReceiptProcessingTest`):

```java
@Test
void failedAndSuccessfulLoginsAreCounted() throws Exception {
    double failBefore = count("gk.login", "outcome", "failure");
    mvc.perform(post("/api/auth/login").with(csrf()).contentType(JSON)
            .content("{\"email\":\"nobody@example.org\",\"password\":\"falsch-falsch-1\"}")).andExpect(status().isUnauthorized());
    assertThat(count("gk.login", "outcome", "failure")).isEqualTo(failBefore + 1);
}

@Test
void anUnreadableUploadIsCountedByReason() throws Exception {
    double before = count("gk.upload.rejected", "reason", "bad_type");
    // POST /api/expenses/capture mit einer Textdatei als "photo" → 400
    ...
    assertThat(count("gk.upload.rejected", "reason", "bad_type")).isEqualTo(before + 1);
}

@Test
void receiptPipelineRecordsDurationResultAndEndToEnd() throws Exception {
    // wie ReceiptProcessingTest.processorFillsInTheReceipt...: Modell liefert gutes Ergebnis
    // erwartet: gk.receipt.ai.results{result=usable} +1, gk.receipt.end_to_end{outcome=EINGEREICHT} count +1,
    // gk.expense.transitions{from=VERARBEITUNG,to=EINGEREICHT} +1, gk.ledger.bookings{type=AUSGABE} +1
}

@Test
void uncertainReceiptCountsGaveUpAndFallback() throws Exception {
    // beide Modelle unsicher → gk.receipt.ai.fallback +1, gk.receipt.ai.gave_up{reason=uncertain} +1,
    // gk.receipt.end_to_end{outcome=MANUELL} +1
}

private double count(String name, String... tags) {
    var c = registry.find(name).tags(tags).counter();
    return c == null ? 0 : c.count();
}
```
(Test-Rumpf mit den echten Helfern aus `ReceiptProcessingTest` ausfüllen: `capture(pk)`, `client.extract(...)` gemockt, `processor.runOnce()`.)

- [ ] **Step 2:** Tests laufen lassen → FAIL.
- [ ] **Step 3:** Änderungen der Dateiliste oben umsetzen. Beispiel `setStatus`:

```java
private void setStatus(Buchung b, BuchungStatus to) {
    metrics.expenseTransition(b.status == null ? "NEU" : b.status.name(), to.name());
    b.status = to;
}
```
Beispiel Client-Timer:
```java
long start = System.nanoTime();
try {
    RawReceipt r = call.get(...);
    metrics.aiCall(model, "ok", Duration.ofNanos(System.nanoTime() - start));
    return Optional.ofNullable(r);
} catch (TimeoutException e) {
    metrics.aiCall(model, "timeout", Duration.ofNanos(System.nanoTime() - start));
    ...
} catch (Exception e) {
    metrics.aiCall(model, "error", Duration.ofNanos(System.nanoTime() - start));
    ...
}
```
Ergebnis-Zuordnung in `ReceiptRecognitionService.attempt`: Ausnahme oder leeres `Optional` → `failed`; `isUncertain()` → `uncertain`; sonst nach der Plausibilitätsprüfung in `recognize`: `usable` oder `implausible` (dafür `aiResult` in `recognize` nach `usable(today)` auslösen statt in `attempt`).

- [ ] **Step 4:** `./mvnw -q test` komplett → alle grün. **Step 5: Commit** `feat(obs): business metrics at ledger, expense, auth, upload and receipt pipeline`

### Task 4: Strukturierte Logs und Request-ID

**Files:**
- Modify: `src/main/resources/application.yml` (`logging.structured.format.console: ${LOG_FORMAT:}` — leer = Klartext), `deploy/docker-compose.yml` (`LOG_FORMAT: logstash`), `docker-compose.yml` (lokal ebenfalls `logstash`)
- Create: `src/main/java/de/grouppurse/web/RequestIdFilter.java`
- Test: `src/test/java/de/grouppurse/web/RequestIdFilterTest.java`

**Interfaces:**
- Produces: MDC-Schlüssel `requestId`; Antwort-Header `X-Request-Id`.

- [ ] **Step 1: Failing test**

```java
class RequestIdFilterTest {
    @Test
    void setsHeaderAndMdcAndClearsAfterwards() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        new RequestIdFilter().doFilter(req, res, (rq, rs) -> seen.set(MDC.get("requestId")));
        assertThat(res.getHeader("X-Request-Id")).isNotBlank().isEqualTo(seen.get());
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void ignoresClientSuppliedIdsThatLookUnsafe() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Request-Id", "evil\nvalue");
        var res = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(req, res, (rq, rs) -> { });
        assertThat(res.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }
}
```

- [ ] **Step 2:** FAIL. **Step 3: Implementierung**

```java
package de.grouppurse.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Vergibt je Anfrage eine Request-ID (MDC + Antwort-Header), damit sich Logzeilen einer Anfrage finden lassen. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String incoming = req.getHeader("X-Request-Id");
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put("requestId", id);
        res.setHeader("X-Request-Id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("requestId");
        }
    }
}
```
(`OncePerRequestFilter` hat öffentliches `doFilter`; der Test ruft es mit Lambda-Chain auf.)

- [ ] **Step 4:** Test + `./mvnw -q test` grün. Manuell: App mit `LOG_FORMAT=logstash` starten, eine Anfrage senden, die Logzeile ist JSON mit `requestId`. Stichprobe: `grep -i "@example\|password" app.log` findet nichts (E-Mails kommen in Logzeilen nur vor, wenn bestehender Code sie loggt: `grep -rn "log\.\(info\|warn\|error\)" src/main | grep -i "email\|mail"` prüfen und ggf. entfernen).
- [ ] **Step 5: Commit** `feat(obs): structured logs and request id`

### Task 5: E2E-Prüfung der Metriken und der Port-Trennung

**Files:**
- Create: `e2e/tests/observability.spec.ts`

- [ ] **Step 1: Test**

```ts
import { test, expect } from '../support/fixtures';
import { URLS } from '../support/ports';

async function metrics(): Promise<string> {
  const res = await fetch(`${URLS.management}/actuator/prometheus`);
  expect(res.status).toBe(200);
  return res.text();
}

function value(text: string, name: string, labels: Record<string, string> = {}): number {
  const line = text.split('\n').find((l) => l.startsWith(name) && Object.entries(labels).every(([k, v]) => l.includes(`${k}="${v}"`)));
  return line ? Number(line.split(' ').pop()) : 0;
}

test.describe('Observability', () => {
  test('metrics are only served on the management port', async () => {
    const pub = await fetch(`${URLS.backend}/actuator/prometheus`);
    expect(pub.status).toBe(404);
    expect((await metrics()).startsWith('# HELP')).toBe(true);
  });

  test('a receipt run is visible in the business metrics, without money or names', async ({ world, llm }) => {
    const before = await metrics();
    await world.manager.api.setBalances(100, 0);
    await world.member.api.capture(1);
    await expect.poll(async () => value(await metrics(), 'gk_receipt_end_to_end_seconds_count', { outcome: 'EINGEREICHT' }), { timeout: 30_000 })
      .toBeGreaterThan(value(before, 'gk_receipt_end_to_end_seconds_count', { outcome: 'EINGEREICHT' }));
    const after = await metrics();
    expect(value(after, 'gk_ledger_bookings_total', { type: 'AUSGABE' })).toBeGreaterThan(value(before, 'gk_ledger_bookings_total', { type: 'AUSGABE' }));
    expect(value(after, 'gk_receipt_ai_duration_seconds_count', { outcome: 'ok' })).toBeGreaterThan(0);
    expect(after).not.toContain(world.manager.email);
    expect(after).not.toContain(world.groupName);
  });

  test('failed logins are counted', async () => {
    const before = value(await metrics(), 'gk_login_total', { outcome: 'failure' });
    await fetch(`${URLS.backend}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: 'x@example.org', password: 'falsch-falsch-1' }) });
    expect(value(await metrics(), 'gk_login_total', { outcome: 'failure' })).toBeGreaterThan(before);
  });
});
```
(Der Login-POST ohne CSRF-Token wird abgelehnt; stattdessen den bestehenden API-Helfer aus `support/api.ts` verwenden, der Cookie+XSRF handhabt, oder `world.member.api` mit falschem Passwort. Beim Umsetzen den vorhandenen Helfer nehmen.)

- [ ] **Step 2:** Bauen (`node scripts/build.mjs`), `SKIP_BUILD=1 npx playwright test tests/observability.spec.ts --project=desktop` → grün; danach die ganze Suite, damit die Port-Änderung aus Task 1 nichts bricht. Spec-Tabelle in `e2e/README.md` ergänzen.
- [ ] **Step 3: Commit** `test(e2e): observability metrics and port separation`

---

## Phase 2: Stack

### Task 6: Monitoring-Stack als Compose-Datei, lokal startbar

**Files (alle neu unter `deploy/monitoring/`):**
- `docker-compose.monitoring.yml`
- `prometheus/prometheus.yml`
- `loki/loki.yml`
- `alloy/config.alloy`
- `blackbox/blackbox.yml`
- `grafana/provisioning/datasources/datasources.yml`
- `grafana/provisioning/dashboards/dashboards.yml`
- Modify: `docker-compose.yml` (lokal): `include`-artig über Profil, siehe Step 3

**Interfaces:**
- Consumes: Management-Port `app:8081`, Docker-Netz des Haupt-Compose-Projekts.
- Produces: Grafana auf Port 3000 (intern), Prometheus-Datasource-UID `prometheus`, Loki-Datasource-UID `loki`.

- [ ] **Step 1: `prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
rule_files:
  - /etc/prometheus/rules/*.yml
scrape_configs:
  - job_name: app
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ['app:8081'] }]
  - job_name: postgres
    static_configs: [{ targets: ['postgres-exporter:9187'] }]
  - job_name: node
    static_configs: [{ targets: ['node-exporter:9100'] }]
  - job_name: cadvisor
    static_configs: [{ targets: ['cadvisor:8080'] }]
  - job_name: caddy
    static_configs: [{ targets: ['caddy:2019'] }]
  - job_name: blackbox
    metrics_path: /probe
    params: { module: [https_2xx] }
    static_configs: [{ targets: ['${PROBE_URL}'] }]   # wird per Compose-Entrypoint ersetzt, siehe unten
    relabel_configs:
      - source_labels: [__address__]
        target_label: __param_target
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: blackbox:9115
```
Die URL ist umgebungsabhängig: Prometheus kennt keine Variablen in der Datei. Deshalb startet der Container mit `--enable-feature=expand-external-labels`-freiem Weg: eine `file_sd`-Datei `prometheus/targets/probe.yml` wird von einem kleinen `sh -c`-Schritt im Compose-`command` aus `${PROBE_URL}` erzeugt (`echo "- targets: ['${PROBE_URL}']" > /etc/prometheus/targets/probe.yml`) und über `file_sd_configs` eingelesen. Lokal ist `PROBE_URL=http://app:8080/actuator/health`... (lokal gibt es 8081 für Health, deshalb `http://app:8081/actuator/health`).

- [ ] **Step 2: Weitere Konfigurationsdateien**

`loki/loki.yml`: Single-Binary, `auth_enabled: false`, Dateisystem-Speicher `/loki`, `limits_config.retention_period: 336h`, `compactor.retention_enabled: true`, Schema `v13`/`tsdb`, `server.http_listen_port: 3100`.

`alloy/config.alloy`:
```
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}
discovery.relabel "gk" {
  targets = discovery.docker.containers.targets
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    regex         = "app|caddy|db"
    action        = "keep"
  }
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    target_label  = "container"
  }
}
loki.source.docker "gk" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.gk.output
  forward_to = [loki.process.json.receiver]
}
loki.process "json" {
  stage.json {
    expressions = { level = "level" }
  }
  stage.labels {
    values = { level = "" }
  }
  forward_to = [loki.write.default.receiver]
}
loki.write "default" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```
`blackbox/blackbox.yml`: Modul `https_2xx` mit `prober: http`, `timeout: 10s`, `http.valid_status_codes: []`, `fail_if_not_ssl: false` (lokal ohne TLS; in Produktion über Regel auf `probe_ssl_earliest_cert_expiry` abgedeckt).

`grafana/provisioning/datasources/datasources.yml`: zwei Datasources `Prometheus` (uid `prometheus`, `http://prometheus:9090`, default) und `Loki` (uid `loki`, `http://loki:3100`). `dashboards/dashboards.yml`: Provider `Gruppenkasse` mit Pfad `/var/lib/grafana/dashboards`, `foldersFromFilesStructure: true`.

- [ ] **Step 3: `docker-compose.monitoring.yml`** — Dienste `prometheus` (`prom/prometheus`, `--storage.tsdb.retention.time=30d --storage.tsdb.retention.size=3GB`, `mem_limit: 300m`), `loki` (`grafana/loki`, 300m), `alloy` (`grafana/alloy`, Docker-Socket `:ro`, 150m), `grafana` (`grafana/grafana`, 250m, Umgebung `GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}`, `GF_USERS_ALLOW_SIGN_UP=false`, `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_SERVER_ROOT_URL=${GRAFANA_ROOT_URL}`, `GF_SECURITY_COOKIE_SECURE=${GRAFANA_COOKIE_SECURE:-true}`), `node-exporter` (`prom/node-exporter`, `pid: host`, Mounts `/proc`, `/sys`, `/` read-only, 30m), `cadvisor` (`gcr.io/cadvisor/cadvisor`, Mounts read-only, 120m), `postgres-exporter` (`prometheuscommunity/postgres-exporter`, `DATA_SOURCE_NAME=postgresql://grouppurse:${DB_PASSWORD}@db:5432/grouppurse?sslmode=disable`, 30m), `blackbox` (`prom/blackbox-exporter`, 30m). Alle ohne `ports:`. Volumes `prometheus_data`, `loki_data`, `grafana_data`. Versionen fest pinnen (aktuelle Tags beim Umsetzen nachsehen, kein `latest`).

Lokal: in `docker-compose.yml` am Ende `include`-frei per Aufruf `docker compose -f docker-compose.yml -f deploy/monitoring/docker-compose.monitoring.yml --profile monitoring up`. Dazu in der Monitoring-Datei jeden Dienst mit `profiles: ["monitoring"]` versehen; nur der Grafana-Dienst veröffentlicht lokal `127.0.0.1:3000:3000` über eine `ports`-Ergänzung in einer kleinen `deploy/monitoring/docker-compose.local.yml` (nicht in Produktion). Der Caddy-Metrikdienst fehlt lokal (kein Caddy), der Scrape-Job `caddy` bleibt, bekommt dort „down“ und wird von der Regel „App down“ nicht erfasst (Regeln filtern auf `job="app"`).

- [ ] **Step 4: Starten und prüfen** (lokal, mit den vorhandenen Compose-Befehlen):
`docker compose -f docker-compose.yml -f deploy/monitoring/docker-compose.monitoring.yml -f deploy/monitoring/docker-compose.local.yml --profile monitoring up --build -d`, dann
`curl -s localhost:3000/api/health`, in Grafana unter Explore: Prometheus-Abfrage `up` zeigt die Jobs `app`, `postgres`, `node`, `cadvisor`, `blackbox` mit 1; Loki-Abfrage `{container="app"}` liefert JSON-Zeilen.
- [ ] **Step 5: Commit** `feat(obs): monitoring stack (prometheus, loki, alloy, grafana, exporters)`

### Task 7: Alarmregeln mit Tests

**Files:**
- Create: `deploy/monitoring/prometheus/rules/gruppenkasse.yml`
- Create: `deploy/monitoring/prometheus/tests/gruppenkasse.test.yml`

- [ ] **Step 1: Regeln** (`groups: - name: gruppenkasse`, jede Regel mit `labels.severity` und `annotations.summary` auf Deutsch):

| alert | expr | for |
| --- | --- | --- |
| AppDown | `up{job="app"} == 0` | 2m |
| HttpsProbeFailed | `probe_success == 0` | 2m |
| CertExpiresSoon | `(probe_ssl_earliest_cert_expiry - time()) < 14*24*3600` | 1h |
| High5xxRate | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.02` | 5m |
| SlowRequests | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m]))) > 2` | 10m |
| HeapHigh | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85` | 10m |
| ConnectionPoolWaiting | `hikaricp_connections_pending > 0` | 5m |
| DiskAlmostFull | `1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"} > 0.85` | 10m |
| MemoryLow | `node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes < 0.10` | 10m |
| ContainerRestarted | `increase(container_start_time_seconds{name=~".+"}[15m]) > 0 and changes(container_start_time_seconds{name=~".+"}[15m]) > 0` | 0m |
| ReceiptAiFailureRate | `sum(rate(gk_receipt_ai_results_total{result=~"failed\|implausible"}[15m])) / sum(rate(gk_receipt_ai_results_total[15m])) > 0.5` | 15m |
| ReceiptsGivenUp | `sum(increase(gk_receipt_ai_gave_up_total[1h])) > 5` | 0m |
| LoginFailuresSpike | `sum(rate(gk_login_total{outcome=~"failure\|locked"}[1m])) * 60 > 10` | 2m |

- [ ] **Step 2: Test** (`promtool test rules`): je Regel mindestens ein Szenario, mindestens `AppDown` (up=0 für 3 min → feuert nach 2 min), `High5xxRate` (30 % 5xx → feuert), `ReceiptsGivenUp` (7 Zähler in einer Stunde → feuert) und ein Negativfall (alles gesund → keine Alarme).
- [ ] **Step 3:** `docker run --rm -v "$PWD/deploy/monitoring/prometheus:/p" --entrypoint promtool prom/prometheus check config /p/prometheus.yml` und `… test rules /p/tests/gruppenkasse.test.yml` → grün.
- [ ] **Step 4: Commit** `feat(obs): alert rules with promtool tests`

### Task 8: Dashboards

**Files:**
- Create: `deploy/monitoring/tools/gen_dashboards.py` (erzeugt die JSON-Dateien aus kompakten Definitionen; läuft mit reinem Python 3, keine Abhängigkeiten)
- Create (generiert, eingecheckt): `deploy/monitoring/grafana/dashboards/technik/{uebersicht,anwendung,datenbank,server,logs}.json`, `…/fachlich/{belegerkennung,betrieb,sicherheit}.json`

**Panels** (Typ `timeseries`, `stat`, `gauge`, `table`, `logs`; Datasource-UIDs `prometheus`/`loki`):

- **Übersicht:** Stat „App läuft“ (`up{job="app"}`), „HTTPS-Check“ (`probe_success`), „Zertifikat läuft ab in Tagen“, „Fehlerquote 5xx“, „p95-Latenz“, „Heap-Auslastung“, „Plattenbelegung“, Tabelle „Aktive Alarme“ (`ALERTS{alertstate="firing"}`).
- **Anwendung:** Anfragen/s nach Status, p50/95/99 je `uri`, Fehler je `uri`, JVM Heap/Non-Heap, GC-Pausen, Threads, Tomcat-Sessions, Hikari aktive/wartende Verbindungen, Logback-Fehler/s (`logback_events_total{level="error"}`), Startzeit.
- **Datenbank:** `pg_stat_database_numbackends`, Commits/Rollbacks, `pg_stat_database_tup_*`, Datenbankgröße (`pg_database_size_bytes`), Sperren (`pg_locks_count`), Hikari-Zeiten (`hikaricp_connections_acquire_seconds`).
- **Server:** CPU, RAM, Swap, Platte, Netz (node); CPU/RAM je Container (cAdvisor), Neustarts.
- **Logs:** Fehlerzeilen (`{container="app", level=~"ERROR|WARN"}`), Logvolumen nach Level, Suche über Dashboard-Variable `requestId`.
- **Belegerkennung:** Trichter (`gk_expense_transitions_total` nach `to`), Dauer p50/95 je Modell (`gk_receipt_ai_duration_seconds_bucket`), Ergebnisse (`gk_receipt_ai_results_total` je `result`), Fallback-Rate, aufgegebene Belege je Grund, Gesamtdauer Foto→Ergebnis p50/95 (`gk_receipt_end_to_end_seconds_bucket`), Token-Verbrauch (`gen_ai_client_token_usage_total` bzw. Namen aus `curl :8081/actuator/prometheus | grep token`).
- **Betrieb:** Buchungen pro Tag nach `type`, Beleg-Statuswechsel, Uploads abgelehnt je Grund.
- **Sicherheit:** Logins nach `outcome`, 401/403/429 je Zeit, Registrierungen/Einladungen nach `outcome`.

- [ ] **Step 1:** Generator schreiben (Funktionen `stat(...)`, `timeseries(...)`, `table(...)`, `logs(...)`, `dashboard(title, uid, panels)`, Raster 24 Spalten, deterministische Panel-IDs) und alle acht Dateien erzeugen: `python deploy/monitoring/tools/gen_dashboards.py`.
- [ ] **Step 2: Validierung:** jede Datei ist gültiges JSON (`python -m json.tool`), eindeutige `uid`s, jede Abfrage verwendet nur Metriknamen, die entweder in Spring/Exportern vorkommen oder in `GkMetrics` definiert sind. Prüfskript `deploy/monitoring/tools/check_dashboards.py`: lädt alle JSONs, sammelt `expr`-Metriknamen per Regex und vergleicht gegen eine Liste der bekannten Präfixe (`up`, `probe_`, `http_server_`, `jvm_`, `hikaricp_`, `tomcat_`, `logback_`, `process_`, `system_`, `node_`, `container_`, `pg_`, `gk_`, `gen_ai_`, `ALERTS`).
- [ ] **Step 3: Sichtprüfung** im lokalen Stack: App benutzen (Beleg aufnehmen, Login), jedes Dashboard öffnen, Panels ohne „No data“ dort, wo Daten erwartet werden. Fehlende Metriknamen im Generator korrigieren und neu erzeugen.
- [ ] **Step 4: Commit** `feat(obs): grafana dashboards`

---

## Phase 3: Deployment und Doku

### Task 9: Produktion, Pipeline, Doku

**Files:**
- Modify: `deploy/docker-compose.yml` (Caddy: Port 2019 intern für Metriken; Netz-Anbindung der Monitoring-Datei; `LOG_FORMAT: logstash`)
- Modify: `deploy/Caddyfile` (Grafana-Block, globaler Block `{ admin :2019 \n metrics }`)
- Modify: `deploy/deploy.sh` (Start mit beiden Compose-Dateien: `docker compose -f docker-compose.yml -f monitoring/docker-compose.monitoring.yml up -d`; `healthy()` bereits auf 8081)
- Modify: `.github/workflows/ci-deploy.yml` (scp des Ordners `deploy/monitoring` mit `-r`; Secrets `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_DOMAIN` in `secrets.env`; deploy bleibt `if: false`)
- Modify: `deploy/env.example`, `docs/betrieb.md` (Abschnitt „Monitoring“: DNS-Record `grafana.<Domain>`, Secrets, Login, Dashboards, Alarme ohne Zustellung, Aufbewahrung, externer Uptime-Check, Speicherbedarf), `frontend/src/components/PrivacyNotice.tsx` (ein Satz zu technischen Betriebsdaten, 14/30 Tage), `README.md` (lokaler Start mit Monitoring)
- Modify: `.github/workflows/ci-deploy.yml` Job `test` oder neuer Job `monitoring-config`: `docker compose config -q` für beide Dateien, `promtool check config/rules` + `test rules`, `python deploy/monitoring/tools/check_dashboards.py`

- [ ] **Step 1:** Caddyfile: globaler Block `{ admin :2019 
 metrics }` (Admin-Endpunkt nur im Docker-Netz, nicht veröffentlicht) und ein zusätzlicher Block `{$GRAFANA_DOMAIN} { reverse_proxy grafana:3000 }`. Dem Caddy-Dienst `GRAFANA_DOMAIN` als Umgebungsvariable mitgeben.
- [ ] **Step 2:** Compose-Validierung lokal: `docker compose -f deploy/docker-compose.yml -f deploy/monitoring/docker-compose.monitoring.yml --env-file deploy/env.example config -q`.
- [ ] **Step 3:** Pipeline-Datei mit `actionlint` prüfen (wie bisher), Doku schreiben.
- [ ] **Step 4:** Gesamtlauf: `./mvnw -q test`, Frontend `npm test` und Build, E2E komplett, lokaler Stack einmal hoch und wieder runter. Danach lokale Dev-Umgebung wie vorher (Backend 8080, Frontend 5173) wiederherstellen.
- [ ] **Step 5: Commit** `feat(obs): production wiring, pipeline checks and docs`; Push nur auf Anweisung.

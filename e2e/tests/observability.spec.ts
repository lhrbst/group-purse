import { test, expect } from '../support/fixtures';
import { Api } from '../support/api';
import { URLS } from '../support/ports';

async function metrics(): Promise<string> {
  const res = await fetch(`${URLS.management}/actuator/prometheus`);
  expect(res.status).toBe(200);
  return res.text();
}

/** Wert einer Zeitreihe aus dem Prometheus-Text, 0 wenn es sie noch nicht gibt. */
function value(text: string, name: string, labels: Record<string, string> = {}): number {
  const line = text
    .split('\n')
    .find((l) => l.startsWith(`${name}{`) && Object.entries(labels).every(([k, v]) => l.includes(`${k}="${v}"`)));
  return line ? Number(line.trim().split(' ').pop()) : 0;
}

test.describe('Observability', () => {
  test('metrics are only served on the internal management port', async () => {
    const publicSide = await fetch(`${URLS.backend}/actuator/prometheus`);
    expect(publicSide.status).toBe(404);
    const publicHealth = await fetch(`${URLS.backend}/actuator/health`);
    expect(publicHealth.status).toBe(404);
    const text = await metrics();
    expect(text).toContain('# HELP');
    expect(text).toContain('http_server_requests_seconds_bucket');
    expect(text).toContain('application="gruppenkasse"');
  });

  test('a receipt run shows up in the business metrics, without money or names', async ({ world }) => {
    const before = await metrics();
    await world.manager.api.setBalances(100, 0);
    await world.member.api.capture(1);
    await expect
      .poll(async () => value(await metrics(), 'gk_receipt_end_to_end_seconds_count', { outcome: 'EINGEREICHT' }), { timeout: 30_000 })
      .toBeGreaterThan(value(before, 'gk_receipt_end_to_end_seconds_count', { outcome: 'EINGEREICHT' }));

    const after = await metrics();
    expect(value(after, 'gk_ledger_bookings_total', { type: 'AUSGABE' })).toBeGreaterThan(value(before, 'gk_ledger_bookings_total', { type: 'AUSGABE' }));
    expect(value(after, 'gk_expense_transitions_total', { from: 'VERARBEITUNG', to: 'EINGEREICHT' })).toBeGreaterThan(
      value(before, 'gk_expense_transitions_total', { from: 'VERARBEITUNG', to: 'EINGEREICHT' }),
    );
    expect(value(after, 'gk_receipt_ai_duration_seconds_count', { outcome: 'ok' })).toBeGreaterThan(
      value(before, 'gk_receipt_ai_duration_seconds_count', { outcome: 'ok' }),
    );
    expect(value(after, 'gk_receipt_ai_results_total', { result: 'usable' })).toBeGreaterThan(value(before, 'gk_receipt_ai_results_total', { result: 'usable' }));
    // Histogramme für die Dashboards (Perzentile) und Token-Verbrauch aus Spring AI
    expect(after).toContain('gk_receipt_ai_duration_seconds_bucket{');
    expect(after).toContain('gk_receipt_end_to_end_seconds_bucket{');
    expect(after).toMatch(/gen_ai_client_token_usage_total\{[^}]*gen_ai_token_type="input"/);
    // keine Personen, Gruppen oder Beträge in den Metriken
    expect(after).not.toContain(world.manager.email);
    expect(after).not.toContain(world.groupName);
    expect(after).not.toContain(world.groupId);
  });

  test('an unsure model is visible as fallback and gave-up counters', async ({ world, llm }) => {
    await llm.mode('unsure');
    const before = await metrics();
    await world.member.api.capture(1);
    await expect
      .poll(async () => value(await metrics(), 'gk_receipt_ai_gave_up_total', { reason: 'uncertain' }), { timeout: 30_000 })
      .toBeGreaterThan(value(before, 'gk_receipt_ai_gave_up_total', { reason: 'uncertain' }));
    const after = await metrics();
    expect(value(after, 'gk_receipt_ai_fallback_total')).toBeGreaterThan(value(before, 'gk_receipt_ai_fallback_total'));
    expect(value(after, 'gk_receipt_end_to_end_seconds_count', { outcome: 'MANUELL' })).toBeGreaterThan(
      value(before, 'gk_receipt_end_to_end_seconds_count', { outcome: 'MANUELL' }),
    );
  });

  test('failed logins and rejected uploads are counted', async ({ playwright, world }) => {
    const before = await metrics();
    const anon = await Api.anonymous(playwright);
    const res = await anon.post('/api/auth/login', { email: `nobody-${Date.now()}@example.org`, password: 'falsch-falsch-1' });
    expect(res.status()).toBe(401);
    await anon.dispose();

    const form = new FormData();
    form.append('photos', new Blob(['das ist kein Bild'], { type: 'image/jpeg' }), 'x.jpg');
    const bad = await world.member.api.multipart('POST', '/api/expenses/capture', form);
    expect(bad.status()).toBe(400);

    const after = await metrics();
    expect(value(after, 'gk_login_total', { outcome: 'failure' })).toBeGreaterThan(value(before, 'gk_login_total', { outcome: 'failure' }));
    expect(value(after, 'gk_upload_rejected_total', { reason: 'bad_type' })).toBeGreaterThan(value(before, 'gk_upload_rejected_total', { reason: 'bad_type' }));
  });

  test('every response carries a request id', async () => {
    const res = await fetch(`${URLS.backend}/api/me`);
    expect(res.headers.get('x-request-id')).toMatch(/^[0-9a-f-]{36}$/);
  });
});

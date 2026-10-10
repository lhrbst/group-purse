import { test, expect, PASSWORD } from '../support/fixtures';
import { Api } from '../support/api';
import { URLS } from '../support/ports';
import { loginViaUi } from '../support/ui';

test.describe('Sicherheit', () => {
  test('API calls without a session are rejected', async ({ playwright }) => {
    const anon = await Api.anonymous(playwright);
    for (const path of ['/api/me', '/api/balance', '/api/members', '/api/expenses/mine']) {
      expect((await anon.get(path)).status(), path).toBe(401);
    }
    await anon.dispose();
  });

  test('state changing requests need the CSRF token', async ({ playwright }) => {
    const ctx = await playwright.request.newContext({ baseURL: URLS.backend });
    const res = await ctx.post('/api/auth/login', { data: { email: 'a@example.org', password: 'x' } });
    expect(res.status()).toBe(403);
    await ctx.dispose();
  });

  test('five wrong passwords lock the account for a while', async ({ playwright, world }) => {
    const anon = await Api.anonymous(playwright);
    for (let i = 0; i < 5; i++) {
      expect((await anon.post('/api/auth/login', { email: world.member.email, password: 'falsch-falsch-falsch' })).status()).toBe(401);
    }
    expect((await anon.post('/api/auth/login', { email: world.member.email, password: PASSWORD })).status()).toBe(429);
    await anon.dispose();
  });

  test('injection in the login form is just a failed login', async ({ page }) => {
    await loginViaUi(page, "' or '1'='1' --@example.org", "' or '1'='1");
    await expect(page).toHaveURL(/\/login/);
  });

  test('cookies and headers are set safely', async ({ page, world }) => {
    await loginViaUi(page, world.manager.email, world.manager.password);
    await expect(page.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
    const cookies = await page.context().cookies();
    const session = cookies.find((c) => c.name === 'JSESSIONID');
    expect(session?.httpOnly).toBe(true);
    expect(session?.sameSite).toBe('Lax');
    expect(cookies.find((c) => c.name === 'XSRF-TOKEN')?.httpOnly).toBe(false);

    const res = await world.manager.api.get('/api/me');
    const headers = res.headers();
    expect(headers['content-security-policy']).toContain("default-src 'self'");
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['x-frame-options']).toBe('DENY');
    expect(headers['referrer-policy']).toBe('same-origin');
  });

  test('stored text is shown as text, never executed', async ({ page, world }) => {
    const evil = '<img src=x onerror="window.__xss=1">Müller';
    await world.manager.api.ok(await world.manager.api.post('/api/families', { name: evil, faelligkeitstag: 5, sollbetrag: 10, gueltigAb: '2026-01-01' }));
    let dialogSeen = false;
    page.on('dialog', async (d) => {
      dialogSeen = true;
      await d.dismiss();
    });
    await page.context().addCookies(await world.manager.api.cookies());
    await page.goto('/beitraege');
    await page.getByRole('tab', { name: 'Familien' }).click();
    await expect(page.getByText(evil)).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
    expect(dialogSeen).toBe(false);
  });

  test('uploads must really be images', async ({ world }) => {
    const form = new FormData();
    form.append('photos', new Blob(['das ist kein Bild'], { type: 'image/png' }), 'fake.png');
    const res = await world.member.api.multipart('POST', '/api/expenses/capture', form);
    expect(res.status()).toBe(400);
    expect((await world.member.api.get('/api/expenses/mine')).ok()).toBe(true);
    expect(await (await world.member.api.get('/api/expenses/mine')).json()).toEqual([]);
  });

  test('receipt photos are only served to the owner and managers of the group', async ({ playwright, world }) => {
    const receipt = await world.member.api.capture(1);
    expect((await world.member.api.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(200);
    expect((await world.manager.api.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(200);
    const colleague = await world.addMember('Kollege');
    expect((await colleague.api.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(403);
    const anon = await Api.anonymous(playwright);
    expect((await anon.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(401);
    await anon.dispose();
  });
});

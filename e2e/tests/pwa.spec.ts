import { test, expect } from '../support/fixtures';

test.describe('Installierbare App (PWA)', () => {
  test('manifest and icons are served', async ({ page, request }) => {
    await page.goto('/login');
    await expect(page).toHaveTitle('Gruppenkasse');
    await expect(page.locator('link[rel="manifest"]')).toHaveCount(1);
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#1c7ed6');

    const manifest = await (await request.get('/manifest.webmanifest')).json();
    expect(manifest).toMatchObject({ name: 'Gruppenkasse', short_name: 'Gruppenkasse', lang: 'de', display: 'standalone', start_url: '/' });
    const sizes = manifest.icons.map((i: { sizes: string }) => i.sizes);
    expect(sizes).toEqual(expect.arrayContaining(['192x192', '512x512']));
    for (const icon of manifest.icons) {
      const res = await request.get(icon.src);
      expect(res.status(), icon.src).toBe(200);
      expect(res.headers()['content-type']).toContain('image/png');
    }
  });

  test('a service worker takes control and the app shell loads without network', async ({ page, context }) => {
    await page.goto('/login');
    await expect
      .poll(() => page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state))
      .toBe('activated');
    // nach der Aktivierung kontrolliert der Service Worker die Seite (beim nächsten Laden)
    await page.reload();
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);

    await context.setOffline(true);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Anmelden' })).toBeVisible();
    await context.setOffline(false);
  });

  test('API responses are never served from the cache', async ({ page, world }) => {
    await page.context().addCookies(await world.manager.api.cookies());
    await page.goto('/');
    await page.evaluate(async () => (await navigator.serviceWorker.ready).active?.state);
    await world.manager.api.setBalances(10, 20);
    await page.reload();
    await expect(page.getByText(/10,00/).first()).toBeVisible();
    await world.manager.api.ok(await world.manager.api.post('/api/ledger/korrektur', { konto: 'BARKASSE', betrag: 5, begruendung: 'Cache-Test' }));
    await page.reload();
    await expect(page.getByText(/15,00/).first()).toBeVisible();
  });
});

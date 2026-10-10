import { test, expect, openAs } from '../support/fixtures';
import { captureAndWait } from '../support/receipts';
import { cameraInput, goTo } from '../support/ui';
import { photo } from '../support/images';

test.describe('Mobil (Pixel 7)', () => {
  test('the navigation sits behind the burger menu and works by touch', async ({ page, world }) => {
    await openAs(page, world.member);
    await expect(page.getByLabel('Menü', { exact: true })).toBeVisible();
    await goTo(page, 'Meine Ausgaben');
    await expect(page.getByRole('heading', { name: 'Meine Ausgaben', exact: true })).toBeVisible();
  });

  test('a team member captures a receipt on the phone and sees its progress', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await openAs(page, world.member);
    const button = page.locator('main').getByRole('link', { name: 'Beleg erfassen', exact: true });
    expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44); // große Touch-Fläche
    await captureAndWait(page);
    await expect(page.getByText(/wird verarbeitet|eingereicht/).first()).toBeVisible();
    await expect(page.getByText('eingereicht', { exact: true })).toBeVisible({ timeout: 40_000 });
  });

  test('the camera input asks for the rear camera and images only', async ({ page, world }) => {
    await openAs(page, world.member, '/beleg');
    const input = cameraInput(page);
    await expect(input).toHaveAttribute('capture', 'environment');
    await expect(input).toHaveAttribute('accept', 'image/*');
    await input.setInputFiles(photo());
    await expect(page.getByRole('img', { name: 'Foto 1' })).toBeVisible();
  });

  test('no page scrolls sideways on a phone', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 100);
    await world.manager.api.ok(await world.manager.api.post('/api/families', { name: 'Müller', faelligkeitstag: 5, sollbetrag: 50, gueltigAb: '2026-01-01' }));
    await openAs(page, world.manager);
    for (const path of ['/', '/pruefung', '/kasse', '/beitraege', '/auswertungen', '/mitglieder', '/einstellungen', '/beleg', '/beleg/ohne-foto']) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${path} läuft ${overflow}px über`).toBeLessThanOrEqual(1);
    }
  });

  test('the manager reviews and approves on the phone', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await world.member.api.capture(1);
    await expect
      .poll(async () => ((await (await world.manager.api.get('/api/expenses/pending')).json()) as { status: string }[])[0]?.status, { timeout: 40_000 })
      .toBe('EINGEREICHT');
    await openAs(page, world.manager, '/pruefung');
    await page.getByRole('button', { name: 'Prüfen und bestätigen' }).click();
    await expect(page.getByText('Ausgabe bestätigt.')).toBeVisible();
  });
});

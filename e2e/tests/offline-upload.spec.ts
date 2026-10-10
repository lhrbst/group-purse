import { test, expect, openAs } from '../support/fixtures';
import { photo } from '../support/images';
import { cameraInput, toast } from '../support/ui';

async function prepare(page: import('@playwright/test').Page, world: import('../support/fixtures').World) {
  await openAs(page, world.member, '/beleg');
  await cameraInput(page).setInputFiles(photo());
  await expect(page.getByRole('img', { name: 'Foto 1' })).toBeVisible();
}

const mineCount = async (world: import('../support/fixtures').World) => ((await (await world.member.api.get('/api/expenses/mine')).json()) as unknown[]).length;

test.describe('Belege lassen sich ohne Netz aufnehmen und gehen nie verloren', () => {
  test('offline: the receipt is stored on the device and sent automatically when the network is back', async ({ page, context, world }) => {
    await prepare(page, world);
    await context.setOffline(true);
    await page.getByRole('button', { name: 'Beleg absenden' }).click();
    await toast(page, 'Kein Netz: Der Beleg ist auf dem Gerät gespeichert und wird automatisch gesendet.');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByText('Belege warten auf den Versand')).toBeVisible();
    expect(await mineCount(world)).toBe(0);

    await context.setOffline(false);
    await expect(page.getByText('Belege warten auf den Versand')).toBeHidden({ timeout: 30_000 });
    await toast(page, 'Beleg gesendet. Er wird jetzt verarbeitet.');
    expect(await mineCount(world)).toBe(1);
  });

  test('a reload before the upload succeeded keeps the photo and sends it on the next start', async ({ page, world }) => {
    await prepare(page, world);
    await page.route('**/api/expenses/capture', (route) => route.abort('connectionfailed'));
    await page.getByRole('button', { name: 'Beleg absenden' }).click();
    await toast(page, /Kein Netz/);
    await expect(page.getByText('Belege warten auf den Versand')).toBeVisible();

    await page.unroute('**/api/expenses/capture');
    await page.reload(); // z. B. Browser lädt die Seite neu oder wurde geschlossen und wieder geöffnet
    await toast(page, 'Beleg gesendet. Er wird jetzt verarbeitet.');
    await expect(page.getByText('Belege warten auf den Versand')).toHaveCount(0);
    expect(await mineCount(world)).toBe(1);
  });

  test('several queued receipts are sent one after the other', async ({ page, world }) => {
    await openAs(page, world.member, '/beleg');
    await page.route('**/api/expenses/capture', (route) => route.abort('connectionfailed'));
    for (let i = 0; i < 2; i++) {
      await page.goto('/beleg');
      await cameraInput(page).setInputFiles(photo());
      await expect(page.getByRole('img', { name: 'Foto 1' })).toBeVisible();
      await page.getByRole('button', { name: 'Beleg absenden' }).click();
      await toast(page, /Kein Netz/);
    }
    await expect(page.getByText('2 Belege sind auf diesem Gerät gespeichert')).toBeVisible();
    await page.unroute('**/api/expenses/capture');
    await page.reload();
    await expect.poll(() => mineCount(world), { timeout: 30_000 }).toBe(2);
    await expect(page.getByText(/Belege warten auf den Versand/)).toBeHidden();
  });

  test('an upload the server refuses for good is reported and not retried forever', async ({ page, world }) => {
    await prepare(page, world);
    await page.route('**/api/expenses/capture', (route) =>
      route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ detail: 'Nur Fotos (JPEG, PNG, WebP) sind erlaubt' }) }));
    await page.getByRole('button', { name: 'Beleg absenden' }).click();
    await toast(page, 'Nur Fotos (JPEG, PNG, WebP) sind erlaubt');
    await expect(page.getByText('Belege warten auf den Versand')).toHaveCount(0);
    await page.reload();
    await expect(page.getByText('Belege warten auf den Versand')).toHaveCount(0);
    expect(await mineCount(world)).toBe(0);
  });

  test('an expired session keeps the photo instead of dropping it', async ({ page, world }) => {
    await prepare(page, world);
    await page.route('**/api/expenses/capture', (route) => route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }));
    await page.getByRole('button', { name: 'Beleg absenden' }).click();
    await expect(page.getByText('Belege warten auf den Versand')).toBeVisible();
    expect(await mineCount(world)).toBe(0);
  });

  test('the capture page keeps the send button disabled until there is a photo and photos can be removed', async ({ page, world }) => {
    await openAs(page, world.member, '/beleg');
    await expect(page.getByRole('button', { name: 'Beleg absenden' })).toBeDisabled();
    await cameraInput(page).setInputFiles(photo());
    await expect(page.getByRole('button', { name: 'Beleg absenden' })).toBeEnabled();
    await page.getByRole('button', { name: 'Entfernen' }).click();
    await expect(page.getByRole('button', { name: 'Beleg absenden' })).toBeDisabled();
    await expect(page.getByRole('link', { name: 'Ohne Foto erfassen' })).toBeVisible();
  });
});

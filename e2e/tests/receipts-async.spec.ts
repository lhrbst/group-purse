import { test, expect, openAs } from '../support/fixtures';
import { photo } from '../support/images';
import { captureAndWait, captureViaUi, LONG } from '../support/receipts';
import { dialog, eur, pick, toast } from '../support/ui';
import { Api } from '../support/api';

type Setup = { manager: { api: Api }; member: { api: Api } };

async function managerSees(world: Setup & { manager: { api: Api } }, status: string, timeout = 40_000) {
  await expect
    .poll(async () => ((await (await world.manager.api.get('/api/expenses/pending')).json()) as { status: string }[]).map((b) => b.status), { timeout })
    .toContain(status);
}

test.describe('Beleg: nur Foto, Verarbeitung im Hintergrund', () => {
  test('photo only: processing is visible, then the AI result arrives and the manager approves', async ({ page, browser, world, llm }) => {
    await world.manager.api.setBalances(100, 0);
    await llm.mode('slow', 2000); // KI braucht 2 Sekunden: so ist der Zwischenzustand sichtbar
    await openAs(page, world.member);
    await captureAndWait(page);

    // Zwischenzustand: Betrag offen, Kassenstand unverändert, Hinweis auf offene Belege
    await expect(page.getByText('wird verarbeitet', { exact: true })).toBeVisible();
    await expect(page.getByText('Betrag offen')).toBeVisible();
    await expect(page.getByText('Der Beleg wird gelesen. Du musst nichts weiter tun.')).toBeVisible();
    await expect(page.getByText('1 Beleg noch ohne Betrag')).toBeVisible();
    expect((await world.manager.api.balance()).barkasse).toBe(100);

    const ctx = await browser.newContext();
    const kv = await ctx.newPage();
    await openAs(kv, world.manager, '/');
    await expect(kv.getByText('Belege ohne Betrag')).toBeVisible();
    await kv.getByRole('link', { name: /^Prüfung/ }).click();
    await expect(kv.getByRole('heading', { name: 'Wird gelesen oder braucht deine Eingabe (1)' })).toBeVisible();

    // Ergebnis kommt von selbst (beide Seiten aktualisieren sich)
    await expect(kv.getByRole('heading', { name: 'Zu prüfen (1)' })).toBeVisible(LONG);
    await expect(kv.getByText('Obst und Gemüse 8,40 €')).toBeVisible();
    await expect(kv.getByText('REWE Testmarkt')).toBeVisible();
    await expect(page.getByText('eingereicht', { exact: true })).toBeVisible(LONG);
    await expect(page.getByText(eur(-23.4))).toBeVisible();
    expect((await world.manager.api.balance()).barkasse).toBe(76.6);

    await kv.getByRole('button', { name: 'Prüfen und bestätigen' }).click();
    await toast(kv, 'Ausgabe bestätigt.');
    await expect(kv.getByText('Alles erledigt. Es gibt keine offenen Belege.')).toBeVisible();
    await ctx.close();

    const calls = await llm.calls();
    expect(calls).toHaveLength(1);
    expect(calls[0].images).toBe(1);
    expect(calls[0].categories).toContain('Obst und Gemüse');
    expect(calls[0].categories).toContain('Haushalt');
    expect(calls[0].categories).not.toContain('Lebensmittel'); // nur wählbare Kategorien (ohne Oberkategorie)
  });

  test('the photo is resized and re-encoded in the browser before it is uploaded', async ({ page, world, llm }) => {
    await openAs(page, world.member);
    await captureAndWait(page, [photo(3000, 2000)]);
    await managerSees(world, 'EINGEREICHT');
    const [call] = await llm.calls();
    expect(call.imageMimes[0]).toMatch(/^image\/(webp|jpeg)$/); // nicht mehr PNG
    expect(call.imageWidths[0]).toBeGreaterThan(0);
    expect(call.imageWidths[0]).toBeLessThanOrEqual(1500); // Original war 3000 Pixel breit
  });

  test('the manager can correct the AI suggestion before approving', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await world.member.api.capture(1);
    await managerSees(world, 'EINGEREICHT');

    await openAs(page, world.manager, '/pruefung');
    await page.getByRole('button', { name: 'Bearbeiten' }).click();
    await expect(dialog(page).getByLabel('Betrag Position 1')).toHaveValue('8,40');
    await expect(dialog(page).getByLabel('Betrag Position 3')).toHaveValue('8,80');
    await expect(dialog(page).getByLabel('Händler')).toHaveValue('REWE Testmarkt');
    await expect(dialog(page).getByText(`Summe: ${eur(23.4)}`)).toBeVisible();

    await dialog(page).getByLabel('Betrag Position 1').fill('10');
    await dialog(page).getByRole('button', { name: 'Position 3 entfernen' }).click();
    await expect(dialog(page).getByText(`Summe: ${eur(16.2)}`)).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Beleg gespeichert. Er kann jetzt geprüft werden.');
    expect((await world.manager.api.balance()).barkasse).toBe(83.8);
    await page.getByRole('button', { name: 'Prüfen und bestätigen' }).click();
    await toast(page, 'Ausgabe bestätigt.');
  });

  test('if the AI fails the receipt goes to manual entry and the manager completes it', async ({ page, world, llm }) => {
    await world.manager.api.setBalances(100, 0);
    await llm.mode('fail');
    await openAs(page, world.member);
    await captureAndWait(page);

    await managerSees(world, 'MANUELL');
    expect((await llm.calls()).length).toBeGreaterThanOrEqual(6); // 3 Versuche mit je 2 Modellen
    expect((await world.manager.api.balance()).barkasse).toBe(100);

    await page.reload();
    await expect(page.getByText('bitte erfassen', { exact: true })).toBeVisible(LONG);
    await expect(page.getByText('Der Kassenverwalter trägt die Daten dieses Belegs ein.')).toBeVisible();

    await openAs(page, world.manager, '/pruefung');
    await expect(page.getByText('Bitte die Daten selbst eintragen.')).toBeVisible();
    await page.getByRole('button', { name: 'Daten erfassen' }).click();
    await expect(dialog(page).getByText('Bitte Betrag, Datum und Kategorien vom Beleg abtippen.')).toBeVisible();
    await expect(dialog(page).getByRole('img', { name: 'Beleg 1' })).toBeVisible();
    await pick(page, 'Kategorie Position 1', 'Haushalt');
    await dialog(page).getByLabel('Betrag Position 1').fill('12,50');
    await dialog(page).getByLabel('Händler').fill('Baumarkt');
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Beleg gespeichert. Er kann jetzt geprüft werden.');
    await expect(page.getByRole('heading', { name: 'Zu prüfen (1)' })).toBeVisible();
    expect((await world.manager.api.balance()).barkasse).toBe(87.5);
    await page.getByRole('button', { name: 'Prüfen und bestätigen' }).click();
    await toast(page, 'Ausgabe bestätigt.');
  });

  for (const mode of ['invalid', 'mismatch'] as const) {
    test(`an unusable AI answer (${mode}) never changes the cash and ends in manual entry`, async ({ world, llm }) => {
      await world.manager.api.setBalances(100, 0);
      await llm.mode(mode);
      const receipt = await world.member.api.capture(1);
      await managerSees(world, 'MANUELL');
      const detail = await (await world.manager.api.get(`/api/expenses/${receipt.id}`)).json();
      expect(detail.status).toBe('MANUELL');
      expect(detail.betrag).toBe(0);
      expect(detail.positionen).toEqual([]);
      expect(detail.verarbeitungFehler).toBeTruthy();
      expect((await world.manager.api.balance()).barkasse).toBe(100);
    });
  }

  test('a model that takes too long is treated as a failure', async ({ world, llm }) => {
    test.setTimeout(120_000);
    await llm.mode('slow', 6000); // Timeout im Teststack: 3 Sekunden
    await world.member.api.capture(1);
    await managerSees(world, 'MANUELL', 90_000);
  });

  test('a long receipt with several photos is read as one', async ({ page, world, llm }) => {
    await openAs(page, world.member);
    await captureAndWait(page, [photo(), photo(), photo()]);
    await managerSees(world, 'EINGEREICHT');
    const calls = await llm.calls();
    expect(calls[0].images).toBe(3);
    const pending = await (await world.manager.api.get('/api/expenses/pending')).json();
    expect(pending[0].belegAnzahl).toBe(3);
  });

  test('a rejected photo is replaced by the team member and read again', async ({ page, browser, world }) => {
    await world.manager.api.setBalances(100, 0);
    await world.member.api.capture(1);
    await managerSees(world, 'EINGEREICHT');
    expect((await world.manager.api.balance()).barkasse).toBe(76.6);

    const ctx = await browser.newContext();
    const kv = await ctx.newPage();
    await openAs(kv, world.manager, '/pruefung');
    await kv.getByRole('button', { name: 'Foto unbrauchbar' }).click();
    await dialog(kv).getByRole('textbox').fill('Bitte neu fotografieren, das Foto ist unscharf');
    await dialog(kv).getByRole('button', { name: 'Zurückgeben' }).click();
    await toast(kv, 'Beleg abgelehnt. Das Teammitglied kann neue Fotos hochladen.');
    await ctx.close();
    expect((await world.manager.api.balance()).barkasse).toBe(76.6); // bleibt abgezogen bis zur Korrektur

    await openAs(page, world.member, '/');
    await expect(page.getByText('abgelehnt', { exact: true })).toBeVisible();
    await expect(page.getByText('Abgelehnt: Bitte neu fotografieren, das Foto ist unscharf')).toBeVisible();
    await page.getByRole('link', { name: 'Beleg neu fotografieren' }).click();
    await expect(page.getByRole('heading', { name: 'Beleg neu fotografieren' })).toBeVisible();
    await expect(page.getByText('Bitte neu fotografieren, das Foto ist unscharf')).toBeVisible();
    await page.locator('input[type=file][capture]').setInputFiles(photo());
    await expect(page.getByRole('img', { name: 'Foto 1' })).toBeVisible();
    await page.getByRole('button', { name: 'Neue Fotos absenden' }).click();
    await toast(page, 'Neue Fotos gesendet. Der Beleg wird erneut verarbeitet.');

    await managerSees(world, 'EINGEREICHT');
    expect((await world.manager.api.balance()).barkasse).toBe(76.6);
    const mine = await (await world.member.api.get('/api/expenses/mine')).json();
    expect(mine).toHaveLength(1);
    expect(mine[0].ablehnungsKommentar).toBeNull();
  });

  test('closing the page right after sending loses nothing', async ({ page, world }) => {
    await openAs(page, world.member);
    await captureViaUi(page);
    await page.close();
    await expect
      .poll(async () => ((await (await world.manager.api.get('/api/expenses/pending')).json()) as unknown[]).length, { timeout: 15_000 })
      .toBe(1);
  });

  test('managers can also capture a photo, and discard a receipt', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await openAs(page, world.manager);
    await captureAndWait(page);
    await managerSees(world, 'EINGEREICHT');
    expect((await world.manager.api.balance()).barkasse).toBe(76.6);

    await page.goto('/pruefung');
    await page.getByRole('button', { name: 'Stornieren' }).click();
    await dialog(page).getByRole('textbox').fill('Privater Einkauf');
    await dialog(page).getByRole('button', { name: 'Stornieren' }).click();
    await toast(page, 'Ausgabe storniert.');
    expect((await world.manager.api.balance()).barkasse).toBe(100);
  });

  test('team members cannot edit or approve receipts', async ({ world }) => {
    const receipt = await world.member.api.capture(1);
    await managerSees(world, 'EINGEREICHT');
    const body = { datum: '2026-01-01', positionen: [{ kategorieId: await world.member.api.categoryId('Haushalt'), betrag: 1 }] };
    expect((await world.member.api.put(`/api/expenses/${receipt.id}/details`, body)).status()).toBe(403);
    expect((await world.member.api.post(`/api/expenses/${receipt.id}/approve`)).status()).toBe(403);
    expect((await world.member.api.post(`/api/expenses/${receipt.id}/reject`, { kommentar: 'x' })).status()).toBe(403);
    // und eingereichte Belege lassen sich nicht neu fotografieren
    const form = new FormData();
    form.append('photos', new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])], { type: 'image/png' }), 'x.png');
    expect((await world.member.api.multipart('PUT', `/api/expenses/${receipt.id}/photos`, form)).status()).toBe(409);
  });
});

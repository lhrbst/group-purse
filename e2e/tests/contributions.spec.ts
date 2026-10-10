import { test, expect, openAs } from '../support/fixtures';
import { isoMonth } from '../support/api';
import { dialog, eur, pick, toast } from '../support/ui';

async function createFamily(world: { manager: { api: import('../support/api').Api } }, name: string, monthsBack = 3, soll = 50) {
  return world.manager.api.ok(await world.manager.api.post('/api/families', {
    name, bewohnerKuerzel: 'T.', kontaktEmail: `${name.toLowerCase()}@example.org`, faelligkeitstag: 5, sollbetrag: soll, gueltigAb: `${isoMonth(-monthsBack)}-01`,
  }), 'Familie anlegen');
}

const badges = (row: import('@playwright/test').Locator, text: string) => row.getByText(text, { exact: true });

test.describe('Elternbeiträge', () => {
  test('manager creates a family and books a payment for the current month', async ({ page, world }) => {
    await openAs(page, world.manager, '/beitraege');
    await page.getByRole('tab', { name: 'Familien' }).click();
    await page.getByRole('button', { name: 'Familie anlegen' }).click();
    await dialog(page).getByLabel('Familienname').fill('Müller');
    await dialog(page).getByLabel('Bewohner (Vorname oder Kürzel)').fill('T.');
    await dialog(page).getByLabel('Kontakt-E-Mail').fill('mueller@example.org');
    await dialog(page).getByLabel('Fälligkeitstag im Monat').fill('5');
    await dialog(page).getByLabel('Monatlicher Sollbetrag (€)').fill('50');
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Familie angelegt.');
    await expect(dialog(page)).toBeHidden();
    const familyRow = page.getByRole('row', { name: /Müller/ });
    await expect(familyRow).toContainText(eur(50));
    await expect(familyRow).toContainText('5.');

    await page.getByRole('tab', { name: 'Raster' }).click();
    const gridRow = page.getByRole('row', { name: /Müller/ });
    await expect(gridRow.getByText(/^(offen|überfällig)$/)).toHaveCount(1);

    await page.getByRole('button', { name: 'Zahlung buchen' }).click();
    await pick(page, 'Familie', 'Müller');
    await expect(dialog(page).getByLabel('Betrag (€)')).toHaveValue('50');
    await dialog(page).getByRole('button', { name: 'Zahlung buchen' }).click();
    await toast(page, 'Zahlungseingang gebucht.');
    await expect(dialog(page)).toBeHidden();
    await expect(badges(gridRow, 'bezahlt')).toHaveCount(1);
    expect((await world.manager.api.balance()).vereinskonto).toBe(50);
  });

  test('payments fill the oldest open months first and overdue months are flagged', async ({ page, world }) => {
    await createFamily(world, 'Schmidt', 3, 50);
    await openAs(page, world.manager, '/beitraege');
    const row = page.getByRole('row', { name: /Schmidt/ });
    await expect(badges(row, 'überfällig').first()).toBeVisible();
    await expect(badges(row, 'bezahlt')).toHaveCount(0);

    // 100 € für "diesen Monat" gebucht, werden trotzdem auf die zwei ältesten offenen Monate verteilt
    await page.getByRole('button', { name: 'Zahlung buchen' }).click();
    await pick(page, 'Familie', 'Schmidt');
    await dialog(page).getByLabel('Betrag (€)').fill('100');
    await dialog(page).getByLabel('Notiz (optional)').fill('Überweisung');
    await dialog(page).getByRole('button', { name: 'Zahlung buchen' }).click();
    await toast(page, 'Zahlungseingang gebucht.');
    await expect(badges(row, 'bezahlt')).toHaveCount(2);
    await expect(badges(row, 'überfällig').first()).toBeVisible();
    expect((await world.manager.api.balance()).vereinskonto).toBe(100);

    // mehr als fällig: Guthaben
    await world.manager.api.ok(await world.manager.api.post('/api/contributions', { familieId: (await createFamilyIdByName(world, 'Schmidt')), betrag: 500, monate: [isoMonth(0)] }));
    await page.reload();
    await expect(row.getByText(eur(400))).toBeVisible(); // 600 gezahlt, 4 Monate x 50 = 200 fällig: 400 Guthaben
  });

  test('a payment can be cancelled and then no longer counts', async ({ page, world }) => {
    await createFamily(world, 'Weber', 1, 50);
    const familyId = await createFamilyIdByName(world, 'Weber');
    await world.manager.api.ok(await world.manager.api.post('/api/contributions', { familieId: familyId, betrag: 50, monate: [isoMonth(-1)], notiz: 'Dauerauftrag' }));
    await openAs(page, world.manager, '/beitraege');
    const row = page.getByRole('row', { name: /Weber/ });
    await expect(badges(row, 'bezahlt')).toHaveCount(1);

    await page.getByRole('tab', { name: 'Zahlungen' }).click();
    const payment = page.getByRole('row', { name: /Weber.*Dauerauftrag/ });
    await expect(payment).toContainText(eur(50));
    await payment.getByRole('button', { name: 'Stornieren' }).click();
    await dialog(page).getByRole('textbox').fill('Falsche Familie');
    await dialog(page).getByRole('button', { name: 'Stornieren' }).click();
    await toast(page, 'Zahlung storniert.');
    await expect(page.getByRole('row', { name: /Weber.*Dauerauftrag/ })).toHaveCount(0);
    await page.getByLabel('Stornierte anzeigen').check();
    await expect(page.getByRole('row', { name: /Weber/ })).toContainText('storniert: Falsche Familie');

    expect((await world.manager.api.balance()).vereinskonto).toBe(0);
    await page.getByRole('tab', { name: 'Raster' }).click();
    await expect(badges(page.getByRole('row', { name: /Weber/ }), 'bezahlt')).toHaveCount(0);
  });

  test('changing the monthly amount only affects later months', async ({ page, world }) => {
    await createFamily(world, 'Fischer', 2, 50);
    await openAs(page, world.manager, '/beitraege');
    await page.getByRole('tab', { name: 'Familien' }).click();
    const row = page.getByRole('row', { name: /Fischer/ });
    await row.getByRole('button', { name: 'Sollbetrag ändern' }).click();
    await dialog(page).getByLabel('Neuer Monatsbeitrag (€)').fill('65');
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Sollbetrag gespeichert. Frühere Monate bleiben unverändert.');
    await expect(row).toContainText(eur(65));

    const familyId = await createFamilyIdByName(world, 'Fischer');
    const history = await world.manager.api.ok<{ betrag: number; gueltigAb: string }[]>(await world.manager.api.get(`/api/families/${familyId}/sollbetraege`));
    expect(history.map((h) => h.betrag)).toEqual([50, 65]);
  });

  test('inactive families disappear from the payment form and validation guides the input', async ({ page, world }) => {
    await createFamily(world, 'Alt', 1, 40);
    await createFamily(world, 'Neu', 1, 40);
    await openAs(page, world.manager, '/beitraege');
    await page.getByRole('tab', { name: 'Familien' }).click();
    await page.getByRole('row', { name: /Alt/ }).getByRole('button', { name: 'Bearbeiten' }).click();
    await dialog(page).getByLabel('Aktiv').click({ force: true });
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Familie gespeichert.');
    await expect(page.getByRole('row', { name: /Alt/ })).toContainText('(inaktiv)');

    await page.getByRole('tab', { name: 'Raster' }).click();
    await page.getByRole('button', { name: 'Zahlung buchen' }).click();
    await dialog(page).getByRole('button', { name: 'Zahlung buchen' }).click();
    await expect(dialog(page).getByText('Bitte eine Familie wählen')).toBeVisible();
    await expect(dialog(page).getByText('Bitte einen Betrag größer 0 eingeben')).toBeVisible();
    await page.getByRole('combobox', { name: 'Familie', exact: true }).click();
    await expect(page.getByRole('option', { name: 'Neu' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Alt' })).toHaveCount(0);
  });

  test('the dashboard shows overdue families and the grid can be paged', async ({ page, world }) => {
    await createFamily(world, 'Braun', 3, 50);
    await openAs(page, world.manager, '/');
    await expect(page.getByText('1 überfällig')).toBeVisible();
    await expect(page.getByText(/1 Familien/)).toBeVisible();

    await page.getByRole('link', { name: 'Elternbeiträge', exact: true }).click();
    const firstHeader = page.getByRole('columnheader').nth(1);
    const before = await firstHeader.textContent();
    await page.getByLabel('Früher').click();
    await expect(firstHeader).not.toHaveText(before!);
    await page.getByRole('button', { name: 'Heute' }).click();
    await expect(firstHeader).toHaveText(before!);
  });
});

async function createFamilyIdByName(world: { manager: { api: import('../support/api').Api } }, name: string): Promise<string> {
  const list = await world.manager.api.ok<{ id: string; name: string }[]>(await world.manager.api.get('/api/families'));
  return list.find((f) => f.name === name)!.id;
}

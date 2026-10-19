import { readFileSync } from 'node:fs';
import { test, expect, openAs } from '../support/fixtures';
import { isoMonth } from '../support/api';
import { eur, pick } from '../support/ui';

const monthName = (offset: number) => {
  const [y, m] = isoMonth(offset).split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleString('de-DE', { month: 'long', year: 'numeric' });
};

test.describe('Monatsübersicht und PDF-Bericht', () => {
  test('the manager sees the month as text and tables and downloads the PDF', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await world.member.api.manualExpense({ positions: [['Obst und Gemüse', 20], ['Haushalt und Drogerie', 10]], haendler: 'REWE' });
    await world.manager.api.manualExpense({ positions: [['Getränke', 5]], haendler: 'Kiosk' });

    await openAs(page, world.manager, '/auswertungen');
    const card = page.locator('.mantine-Card-root', { has: page.getByRole('heading', { name: 'Monatsübersicht' }) });
    await expect(card.getByText(/wurden 35,00\s€ für 2 Belege ausgegeben/)).toBeVisible();
    await expect(card.getByRole('columnheader', { name: 'Hauptkategorie' })).toBeVisible();
    await expect(card.getByRole('cell', { name: 'REWE' })).toBeVisible();
    await expect(card.getByRole('cell', { name: 'Kiosk' })).toBeVisible();
    await expect(card.getByRole('cell', { name: eur(35) })).toBeVisible();

    const [download] = await Promise.all([page.waitForEvent('download'), card.getByRole('link', { name: 'PDF herunterladen' }).click()]);
    expect(download.suggestedFilename()).toBe(`Gruppenkasse-${isoMonth(0)}.pdf`);
    const bytes = readFileSync(await download.path());
    expect(bytes.subarray(0, 4).toString()).toBe('%PDF');
    expect(bytes.length).toBeGreaterThan(1500);
  });

  test('an empty month says so and the month can be switched', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await world.member.api.manualExpense({ positions: [['Haushalt und Drogerie', 12]], haendler: 'Baumarkt' });
    await openAs(page, world.manager, '/auswertungen');
    const card = page.locator('.mantine-Card-root', { has: page.getByRole('heading', { name: 'Monatsübersicht' }) });
    await expect(card.getByRole('cell', { name: 'Baumarkt' })).toBeVisible();

    await card.getByRole('button', { name: 'Vormonat' }).click();
    await expect(card.getByText('Keine Ausgaben in diesem Monat.')).toBeVisible();
    await expect(card.getByRole('columnheader', { name: 'Hauptkategorie' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Nächster Monat' })).toBeEnabled();

    await pick(page, 'Monat', monthName(0));
    await expect(card.getByRole('cell', { name: 'Baumarkt' })).toBeVisible();
    await expect(card.getByRole('button', { name: 'Nächster Monat' })).toBeDisabled();
  });

  test('team members cannot read or download the report', async ({ world }) => {
    const monat = isoMonth(0);
    expect((await world.member.api.get(`/api/reports/monthly?monat=${monat}`)).status()).toBe(403);
    expect((await world.member.api.get(`/api/reports/monthly.pdf?monat=${monat}`)).status()).toBe(403);
    expect((await world.manager.api.get(`/api/reports/monthly.pdf?monat=${isoMonth(1)}`)).status()).toBe(400);
  });
});

import { test, expect, openAs } from '../support/fixtures';
import { isoMonth } from '../support/api';
import { eur, pick } from '../support/ui';

test.describe('Auswertungen', () => {
  test('without data the charts say so', async ({ page, world }) => {
    await openAs(page, world.manager, '/auswertungen');
    await expect(page.getByRole('heading', { name: 'Auswertungen', exact: true })).toBeVisible();
    await expect(page.getByText('Keine Ausgaben im gewählten Zeitraum.')).toBeVisible();
  });

  test('charts show expenses by category, income against expenses and the balance history', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 1000);
    await world.member.api.manualExpense({ positions: [['Obst und Gemüse', 20], ['Haushalt und Drogerie', 10]], haendler: 'REWE' });
    await world.manager.api.manualExpense({ positions: [['Getränke', 5]], haendler: 'Kiosk' });
    const family = await world.manager.api.ok(await world.manager.api.post('/api/families', { name: 'Müller', faelligkeitstag: 5, sollbetrag: 50, gueltigAb: `${isoMonth(-1)}-01` }));
    await world.manager.api.ok(await world.manager.api.post('/api/contributions', { familieId: family.id, betrag: 100, monate: [isoMonth(-1), isoMonth(0)] }));

    await openAs(page, world.manager, '/auswertungen');
    for (const title of ['Ausgaben nach Kategorie und Monat', 'Einnahmen gegen Ausgaben', 'Verlauf des Kassenstands']) {
      await expect(page.getByRole('heading', { name: title })).toBeVisible();
    }
    await expect(page.locator('.recharts-wrapper')).toHaveCount(3);

    // Standard: Unterkategorien; Umschalten auf Hauptkategorien fasst Lebensmittel zusammen
    const firstChart = page.locator('.recharts-wrapper').first();
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Obst und Gemüse$/ })).toBeVisible();
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Getränke$/ })).toBeVisible();
    await page.getByText('Hauptkategorien', { exact: true }).click();
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Lebensmittel$/ })).toBeVisible();
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Obst und Gemüse$/ })).toHaveCount(0);
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Haushalt und Drogerie$/ })).toBeVisible();

    // Filter auf eine Hauptkategorie schließt ihre Unterkategorien ein
    await page.getByText('Unterkategorien', { exact: true }).click();
    await pick(page, 'Kategorie', 'Lebensmittel');
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Obst und Gemüse$/ })).toBeVisible();
    await expect(firstChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Haushalt und Drogerie$/ })).toHaveCount(0);

    const incomeChart = page.locator('.recharts-wrapper').nth(1);
    await expect(incomeChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Einnahmen$/ })).toBeVisible();
    await expect(incomeChart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Ausgaben$/ })).toBeVisible();

    await page.getByText('Vereinskonto', { exact: true }).click();
    await expect(page.locator('.recharts-wrapper')).toHaveCount(3);
    expect(eur(0)).toContain('€');
  });

  test('the expense chart can be filtered by the person who recorded the receipt', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await world.member.api.manualExpense({ positions: [['Haushalt und Drogerie', 10]], haendler: 'A' });
    await world.manager.api.manualExpense({ positions: [['Fahrten', 7]], haendler: 'B' });
    await openAs(page, world.manager, '/auswertungen');
    const chart = page.locator('.recharts-wrapper').first();
    await expect(chart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Fahrten$/ })).toBeVisible();
    await pick(page, 'Erfasst von', world.member.name);
    await expect(chart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Haushalt und Drogerie$/ })).toBeVisible();
    await expect(chart.locator('.mantine-ChartLegend-legendItemName', { hasText: /^Fahrten$/ })).toHaveCount(0);
  });

  test('cancelled expenses do not appear in the charts', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    const receipt = await world.member.api.manualExpense({ positions: [['Haushalt und Drogerie', 10]], haendler: 'Weg damit' });
    await world.manager.api.ok(await world.manager.api.post(`/api/ledger/${receipt.id}/storno`, { begruendung: 'Irrtum' }));
    await openAs(page, world.manager, '/auswertungen');
    await expect(page.getByText('Keine Ausgaben im gewählten Zeitraum.')).toBeVisible();
  });
});

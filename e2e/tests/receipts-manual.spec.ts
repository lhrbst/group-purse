import { test, expect, openAs } from '../support/fixtures';
import { dialog, eur, pick, toast } from '../support/ui';

test.describe('Belege von Hand erfassen und prüfen', () => {
  test('a receipt without photo needs a reason and is deducted at once', async ({ page, browser, world }) => {
    await world.manager.api.setBalances(100, 0);
    await openAs(page, world.member, '/beleg/ohne-foto');

    await page.getByRole('button', { name: 'Absenden' }).click();
    await expect(page.getByText('Bitte eine Kategorie wählen')).toBeVisible();
    await expect(page.getByText('Bitte einen Betrag größer 0 eingeben')).toBeVisible();

    await pick(page, 'Kategorie Position 1', 'Lebensmittel › Obst und Gemüse');
    await page.getByLabel('Betrag Position 1').fill('8,40');
    await page.getByRole('button', { name: 'Absenden' }).click();
    await expect(page.getByText('Ohne Belegfoto ist eine Begründung Pflicht')).toBeVisible();

    // zweite Position, wieder entfernen, wieder hinzufügen: die Summe folgt
    await page.getByRole('button', { name: '+ Weitere Kategorie' }).click();
    await pick(page, 'Kategorie Position 2', 'Haushalt');
    await page.getByLabel('Betrag Position 2').fill('4,99');
    await expect(page.getByText(`Summe: ${eur(13.39)}`)).toBeVisible();
    await page.getByRole('button', { name: 'Position 2 entfernen' }).click();
    await expect(page.getByText(`Summe: ${eur(8.4)}`)).toBeVisible();
    await page.getByRole('button', { name: '+ Weitere Kategorie' }).click();
    await pick(page, 'Kategorie Position 2', 'Haushalt');
    await page.getByLabel('Betrag Position 2').fill('4,99');

    await page.getByLabel('Händler').fill('REWE');
    await page.getByLabel('Zweck').fill('Wocheneinkauf');
    await page.getByLabel('Begründung ohne Beleg').fill('Bon verloren');
    await page.getByRole('button', { name: 'Absenden' }).click();
    await toast(page, 'Ausgabe eingereicht.');
    await expect(page).toHaveURL(/\/$/);

    await expect(page.getByText('REWE')).toBeVisible();
    await expect(page.getByText('eingereicht', { exact: true })).toBeVisible();
    await expect(page.getByText('Lebensmittel › Obst und Gemüse 8,40 € · Haushalt 4,99 €')).toBeVisible();
    await expect(page.getByText('Ohne Beleg: Bon verloren')).toBeVisible();
    expect((await world.manager.api.balance()).barkasse).toBeCloseTo(86.61, 2);

    // Kassenverwalter prüft: ohne Beleg ist markiert
    const ctx = await browser.newContext();
    const kv = await ctx.newPage();
    await ctx.addCookies(await world.manager.api.cookies());
    await kv.goto('/pruefung');
    await expect(kv.getByRole('heading', { name: 'Zu prüfen (1)' })).toBeVisible();
    await expect(kv.getByText('Ohne Beleg: Bon verloren')).toBeVisible();
    await kv.getByRole('button', { name: 'Prüfen und bestätigen' }).click();
    await toast(kv, 'Ausgabe bestätigt.');
    await expect(kv.getByText('Alles erledigt. Es gibt keine offenen Belege.')).toBeVisible();
    await kv.getByRole('tab', { name: 'Alle Ausgaben' }).click();
    await expect(kv.getByRole('tabpanel', { name: 'Alle Ausgaben' }).getByText('geprüft', { exact: true })).toBeVisible();
    await ctx.close();
  });

  test('the same category twice on one receipt is merged into one position', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await openAs(page, world.member, '/beleg/ohne-foto');
    for (const [i, amount] of [[1, '5'], [2, '3,50']] as const) {
      if (i === 2) await page.getByRole('button', { name: '+ Weitere Kategorie' }).click();
      await pick(page, `Kategorie Position ${i}`, 'Haushalt');
      await page.getByLabel(`Betrag Position ${i}`).fill(amount);
    }
    await page.getByLabel('Begründung ohne Beleg').fill('Kein Bon');
    await page.getByRole('button', { name: 'Absenden' }).click();
    await toast(page, 'Ausgabe eingereicht.');
    await expect(page.getByText(`Haushalt ${eur(8.5)}`)).toBeVisible();
  });

  test('amounts with more than two decimals or no category are rejected', async ({ page, world }) => {
    await openAs(page, world.member, '/beleg/ohne-foto');
    await pick(page, 'Kategorie Position 1', 'Haushalt');
    await page.getByLabel('Betrag Position 1').fill('1,234');
    await page.getByRole('button', { name: 'Absenden' }).click();
    await expect(page.getByText('Höchstens zwei Nachkommastellen')).toBeVisible();
    await page.getByLabel('Betrag Position 1').fill('0');
    await page.getByRole('button', { name: 'Absenden' }).click();
    await expect(page.getByText('Bitte einen Betrag größer 0 eingeben')).toBeVisible();
  });

  test('the manager edits a receipt before approving and can cancel it', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    await world.member.api.manualExpense({ positions: [['Haushalt', 30]], haendler: 'Baumarkt', zweck: 'Schrauben' });
    expect((await world.manager.api.balance()).barkasse).toBe(70);

    await openAs(page, world.manager, '/pruefung');
    await expect(page.getByText('Haushalt 30,00 €')).toBeVisible();
    await page.getByRole('button', { name: 'Bearbeiten' }).click();
    await expect(dialog(page).getByRole('heading', { name: 'Beleg bearbeiten' }).or(dialog(page).getByText('Beleg bearbeiten'))).toBeVisible();
    await dialog(page).getByLabel('Betrag Position 1').fill('25');
    await dialog(page).getByRole('button', { name: '+ Weitere Kategorie' }).click();
    await pick(page, 'Kategorie Position 2', 'Lebensmittel › Getränke');
    await dialog(page).getByLabel('Betrag Position 2').fill('4,50');
    await expect(dialog(page).getByText(`Summe: ${eur(29.5)}`)).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Speichern' }).click();
    await toast(page, 'Beleg gespeichert. Er kann jetzt geprüft werden.');
    expect((await world.manager.api.balance()).barkasse).toBe(70.5);
    await expect(page.getByText('Haushalt 25,00 € · Lebensmittel › Getränke 4,50 €')).toBeVisible();

    await page.getByRole('button', { name: 'Stornieren' }).click();
    await dialog(page).getByRole('textbox').fill('Doppelt erfasst');
    await dialog(page).getByRole('button', { name: 'Stornieren' }).click();
    await toast(page, 'Ausgabe storniert.');
    expect((await world.manager.api.balance()).barkasse).toBe(100);
    await page.getByRole('tab', { name: 'Alle Ausgaben' }).click();
    await expect(page.getByText('storniert', { exact: true }).first()).toBeVisible();
  });

  test('a bank account expense is booked by the manager and leaves the cash untouched', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 1000);
    await openAs(page, world.manager, '/kasse');
    await page.getByRole('button', { name: 'Kontoausgabe', exact: true }).click();
    await pick(page, 'Kategorie Position 1', 'Anschaffungen');
    await dialog(page).getByLabel('Betrag Position 1').fill('250');
    await dialog(page).getByLabel('Zweck').fill('Waschmaschine');
    await dialog(page).getByLabel('Begründung ohne Beleg').fill('Rechnung kommt per Post');
    await dialog(page).getByRole('button', { name: 'Kontoausgabe buchen' }).click();
    await toast(page, 'Kontoausgabe gebucht.');
    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 100, vereinskonto: 750 });
    await expect(page.getByRole('row', { name: /Kontoausgabe/ })).toContainText('Waschmaschine');
  });

  test('a team member only sees their own expenses', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 0);
    const colleague = await world.addMember('Kollege');
    await world.member.api.manualExpense({ positions: [['Haushalt', 10]], haendler: 'Meins' });
    await colleague.api.manualExpense({ positions: [['Haushalt', 12]], haendler: 'Deins' });
    await openAs(page, world.member, '/meine-ausgaben');
    await expect(page.getByText('Meins')).toBeVisible();
    await expect(page.getByText('Deins')).toHaveCount(0);
  });
});

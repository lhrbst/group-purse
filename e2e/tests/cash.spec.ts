import { test, expect, openAs } from '../support/fixtures';
import { dialog, eur, pick, toast } from '../support/ui';

test.describe('Kasse: Konten, Buchungen, Bestand', () => {
  test('books opening balances, refill, return and a count correction through the forms', async ({ page, world }) => {
    await openAs(page, world.manager, '/kasse');
    await expect(page.getByRole('heading', { name: 'Kasse', exact: true })).toBeVisible();

    // Anfangsbestände
    await page.getByRole('button', { name: 'Anfangsbestand', exact: true }).click();
    await dialog(page).getByText('Vereinskonto', { exact: true }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('1000');
    await dialog(page).getByRole('button', { name: 'Anfangsbestand buchen' }).click();
    await toast(page, 'Anfangsbestand gebucht.');
    await expect(dialog(page)).toBeHidden();

    await page.getByRole('button', { name: 'Anfangsbestand', exact: true }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('100');
    await dialog(page).getByRole('button', { name: 'Anfangsbestand buchen' }).click();
    await expect(dialog(page)).toBeHidden();
    await expect(page.getByText(eur(1000)).first()).toBeVisible();
    await expect(page.getByText(eur(100)).first()).toBeVisible();

    // Auffüllung: Vereinskonto -> Barkasse
    await page.getByRole('button', { name: 'Barkasse auffüllen' }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('40');
    await dialog(page).getByLabel('Übergeben an').fill('Kasse direkt');
    await dialog(page).getByRole('button', { name: 'Auffüllung buchen' }).click();
    await toast(page, 'Barkasse aufgefüllt.');
    await expect(dialog(page)).toBeHidden();
    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 140, vereinskonto: 960 });
    await expect(page.getByRole('row', { name: /Auffüllung/ })).toHaveCount(2);

    // Rückgabe ans Konto: Barkasse -> Vereinskonto
    await page.getByRole('button', { name: 'Rückgabe ans Konto' }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('20');
    await dialog(page).getByLabel('Grund').fill('Zu viel Bargeld');
    await dialog(page).getByRole('button', { name: 'Rückgabe buchen' }).click();
    await toast(page, 'Rückgabe ans Konto gebucht.');
    await expect(dialog(page)).toBeHidden();
    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 120, vereinskonto: 980 });

    // Bestand erfassen mit Differenz (5 € weniger gezählt)
    await page.getByRole('button', { name: 'Bestand erfassen' }).click();
    await expect(dialog(page).getByText('Berechneter Stand (Soll)')).toBeVisible();
    await dialog(page).getByLabel('Tatsächlicher Stand (Ist)').fill('115');
    await expect(dialog(page).getByText('Differenz:')).toContainText(eur(-5));
    await dialog(page).getByLabel('Begründung').fill('Kaffee gekauft');
    await dialog(page).getByRole('button', { name: 'Bestand erfassen' }).click();
    await toast(page, /Bestand erfasst\. Differenz von .* als Korrektur gebucht\./);
    await expect(dialog(page)).toBeHidden();
    expect((await world.manager.api.balance()).barkasse).toBe(115);
    await expect(page.getByRole('row', { name: /Korrektur/ })).toHaveCount(1);

    // Bestand ohne Differenz
    await page.getByRole('button', { name: 'Bestand erfassen' }).click();
    await dialog(page).getByLabel('Tatsächlicher Stand (Ist)').fill('115');
    await expect(dialog(page).getByText(/alles stimmt/)).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Bestand erfassen' }).click();
    await toast(page, 'Bestand erfasst. Keine Differenz.');
  });

  test('cancelling a booking reverses both legs of a transfer', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 1000);
    await world.manager.api.ok(await world.manager.api.post('/api/ledger/rueckgabe', { betrag: 30, grund: 'Überschuss' }));
    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 70, vereinskonto: 1030 });

    await openAs(page, world.manager, '/kasse');
    await page.getByRole('row', { name: /Rückgabe ans Konto/ }).first().getByRole('button', { name: 'Stornieren' }).click();
    await expect(dialog(page).getByText(/Buchung Nr\. \d+ stornieren/)).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Stornieren' }).click();
    await expect(dialog(page).getByText('Bitte eine Begründung eingeben')).toBeVisible();
    await dialog(page).getByRole('textbox').fill('Falsch gebucht');
    await dialog(page).getByRole('button', { name: 'Stornieren' }).click();
    await toast(page, /Buchung storniert/);
    await expect(dialog(page)).toBeHidden();

    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 100, vereinskonto: 1000 });
    await expect(page.getByRole('row', { name: /Rückgabe ans Konto.*storniert/ })).toHaveCount(2);
    await expect(page.getByRole('row', { name: /Storno zu Nr/ })).toHaveCount(2);
    // ein Storno lässt sich nicht noch einmal stornieren
    await expect(page.getByRole('row', { name: /Storno zu Nr/ }).first().getByRole('button', { name: 'Stornieren' })).toHaveCount(0);
  });

  test('forms validate amounts and required fields', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 100);
    await openAs(page, world.manager, '/kasse');

    await page.getByRole('button', { name: 'Barkasse auffüllen' }).click();
    await dialog(page).getByRole('button', { name: 'Auffüllung buchen' }).click();
    await expect(dialog(page).getByText('Bitte einen Betrag größer 0 eingeben')).toBeVisible();
    await expect(dialog(page).getByText('Bitte angeben, an wen übergeben wurde')).toBeVisible();
    await dialog(page).getByLabel('Betrag (€)').fill('10,123');
    await dialog(page).getByLabel('Übergeben an').fill('Kasse');
    await dialog(page).getByRole('button', { name: 'Auffüllung buchen' }).click();
    await expect(dialog(page).getByText('Höchstens zwei Nachkommastellen')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toBeHidden();

    await page.getByRole('button', { name: 'Korrektur', exact: true }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('0');
    await dialog(page).getByLabel('Begründung').fill('Nichts');
    await dialog(page).getByRole('button', { name: 'Korrektur buchen' }).click();
    await expect(dialog(page).getByText('Bitte einen Betrag ungleich 0 eingeben (Minus für Abzug)')).toBeVisible();
    await page.keyboard.press('Escape');

    // zweiter Anfangsbestand für dasselbe Konto wird vom Server abgelehnt
    await page.getByRole('button', { name: 'Anfangsbestand', exact: true }).click();
    await dialog(page).getByLabel('Betrag (€)').fill('5');
    await dialog(page).getByRole('button', { name: 'Anfangsbestand buchen' }).click();
    await toast(page, 'Für dieses Konto gibt es bereits einen Anfangsbestand');
  });

  test('the ledger can be filtered by account', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 1000);
    await openAs(page, world.manager, '/kasse');
    await expect(page.getByRole('row', { name: /Anfangsbestand/ })).toHaveCount(2);
    await pick(page, 'Konto', 'Vereinskonto');
    await expect(page.getByRole('row', { name: /Anfangsbestand/ })).toHaveCount(1);
    await expect(page.getByRole('row', { name: /Anfangsbestand/ })).toContainText('Vereinskonto');
  });

  test('a low cash balance is flagged for everyone and the minimum is configurable', async ({ page, browser, world }) => {
    await world.manager.api.setBalances(30, 500);
    const memberCtx = await browser.newContext();
    const member = await memberCtx.newPage();
    await memberCtx.addCookies(await world.member.api.cookies());
    await member.goto('/');
    await expect(member.getByText('Wenig Geld in der Barkasse')).toBeVisible();

    await openAs(page, world.manager, '/');
    await expect(page.getByText('Wenig Geld in der Barkasse')).toBeVisible();

    await page.getByRole('link', { name: 'Einstellungen', exact: true }).click();
    await page.getByLabel('Mindestbetrag der Barkasse (€)').fill('20');
    await page.getByRole('button', { name: 'Speichern' }).first().click();
    await toast(page, 'Gespeichert.');

    await member.reload();
    await expect(member.getByText('Aktueller Kassenstand')).toBeVisible();
    await expect(member.getByText('Wenig Geld in der Barkasse')).toHaveCount(0);
    await memberCtx.close();
  });

  test('an overdrawn cash box is shown as a minus', async ({ page, browser, world }) => {
    await world.manager.api.setBalances(30, 500);
    await world.member.api.manualExpense({ positions: [['Haushalt und Drogerie', 40]], haendler: 'Baumarkt' });
    expect((await world.manager.api.balance()).negativ).toBe(true);

    await openAs(page, world.manager, '/');
    await expect(page.getByText('Die Barkasse ist im Minus')).toBeVisible();
    const ctx = await browser.newContext();
    const member = await ctx.newPage();
    await ctx.addCookies(await world.member.api.cookies());
    await member.goto('/');
    await expect(member.getByText('Die Barkasse ist im Minus')).toBeVisible();
    await ctx.close();
  });
});

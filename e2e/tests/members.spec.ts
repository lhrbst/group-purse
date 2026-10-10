import { test, expect } from '../support/fixtures';
import { dialog, goTo } from '../support/ui';

test.describe('Mitglieder und Beitrittscode', () => {
  test('manager blocks and unblocks a team member', async ({ page, browser, world }) => {
    const memberCtx = await browser.newContext();
    const member = await memberCtx.newPage();
    await memberCtx.addCookies(await world.member.api.cookies());
    await member.goto('/');
    await expect(member.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();

    await page.context().addCookies(await world.manager.api.cookies());
    await page.goto('/mitglieder');
    const row = page.getByRole('row', { name: new RegExp(world.member.email) });
    await expect(row).toContainText('aktiv');
    await row.getByRole('button', { name: 'Sperren' }).click();
    await expect(page.getByText(`${world.member.name} ist gesperrt.`)).toBeVisible();
    await expect(row).toContainText('gesperrt');

    // sofort ohne Zugriff, auch mit bestehender Sitzung; der Name bleibt in der Liste
    await member.reload();
    await expect(member.getByText('Noch kein Zugriff')).toBeVisible();
    await expect(member.getByText(`${world.groupName}: gesperrt`)).toBeVisible();
    expect((await world.member.api.get('/api/balance')).status()).toBe(403);

    await row.getByRole('button', { name: 'Wieder freischalten' }).click();
    await expect(page.getByText(`${world.member.name} ist freigeschaltet.`)).toBeVisible();
    await member.getByRole('button', { name: 'Status neu prüfen' }).click();
    await expect(member.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();
    await memberCtx.close();
  });

  test('a new join code invalidates the old one', async ({ page, world }) => {
    await page.context().addCookies(await world.manager.api.cookies());
    await page.goto('/mitglieder');
    const codeText = page.getByRole('heading', { level: 3 }).filter({ hasText: /^[A-Z2-9]{8}$/ });
    await expect(codeText).toHaveText(world.code);

    await page.getByRole('button', { name: 'Neu erzeugen' }).click();
    await dialog(page).getByRole('button', { name: 'Neu erzeugen' }).click();
    await expect(page.getByText('Neuer Beitrittscode erzeugt.')).toBeVisible();
    await expect(codeText).not.toHaveText(world.code);

    await page.goto('/registrieren');
    await page.getByLabel('Beitrittscode').fill(world.code);
    await page.getByLabel('Name').fill('Zu Spät');
    await page.getByLabel('E-Mail').fill(`spaet-${Date.now()}@example.org`);
    await page.getByLabel('Passwort', { exact: true }).fill('Ein-langes-Passwort-1');
    await page.getByLabel('Ich habe den Datenschutzhinweis gelesen und stimme zu.').check();
    await page.getByRole('button', { name: 'Registrieren' }).click();
    await expect(page.getByText('Beitrittscode ungültig')).toBeVisible();
  });

  test('the member list shows roles and statuses and the manager cannot lock out managers', async ({ page, world }) => {
    await page.context().addCookies(await world.manager.api.cookies());
    await page.goto('/mitglieder');
    const own = page.getByRole('row', { name: new RegExp(world.manager.email) });
    await expect(own).toContainText('Kassenverwalter');
    await expect(own.getByRole('button', { name: 'Sperren' })).toHaveCount(0);
    await expect(own.getByRole('button', { name: 'Passwort-Link' })).toHaveCount(0);
    const member = page.getByRole('row', { name: new RegExp(world.member.email) });
    await expect(member).toContainText('Teammitglied');
    await goTo(page, 'Übersicht');
  });
});

import { test, expect, adminLogin, createWorld, openAs } from '../support/fixtures';
import { ADMIN } from '../support/ports';
import { dialog, loginViaUi, pick } from '../support/ui';
import { eur } from '../support/ui';

test.describe('Admin: Wohngruppen und Einladungen', () => {
  test('admin creates a group, invites a manager and the manager accepts the link', async ({ page, browser }) => {
    const groupName = `WG Sonnenhof ${Date.now().toString(36)}`;
    await loginViaUi(page, ADMIN.email, ADMIN.password);
    await expect(page.getByRole('heading', { name: 'Wohngruppen', exact: true })).toBeVisible();

    await page.getByLabel('Neue Wohngruppe').fill(groupName);
    await page.getByRole('button', { name: 'Anlegen' }).click();
    await expect(page.getByText('Wohngruppe angelegt.')).toBeVisible();
    const row = page.getByRole('row', { name: new RegExp(groupName) });
    await expect(row).toContainText(/[A-Z2-9]{8}/);
    await expect(row).toContainText(eur(50));

    await row.getByRole('button', { name: 'Kassenverwalter einladen' }).click();
    const email = `neuer-kv-${Date.now()}@example.org`;
    await dialog(page).getByLabel('E-Mail').fill(email);
    await dialog(page).getByRole('button', { name: 'Einladung senden' }).click();
    await expect(dialog(page).getByText('Einladung erzeugt')).toBeVisible();
    const link = (await dialog(page).getByText(/\/einladung\//).textContent())!.trim();

    const ctx = await browser.newContext();
    const kv = await ctx.newPage();
    await kv.goto(link);
    await expect(kv.getByRole('heading', { name: 'Einladung als Kassenverwalter' })).toBeVisible();
    await expect(kv.getByText(groupName).first()).toBeVisible();
    await kv.getByLabel('Name').fill('Karla Neu');
    await kv.getByLabel('Passwort', { exact: true }).fill('Passwort-fuer-Karla-1');
    await kv.getByLabel('Ich habe den Datenschutzhinweis gelesen und stimme zu.').check();
    await kv.getByRole('button', { name: 'Konto anlegen und annehmen' }).click();

    await expect(kv.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
    await expect(kv.getByRole('link', { name: 'Wohngruppen (Admin)' })).toHaveCount(0);
    // Einladung ist verbraucht
    const again = await ctx.newPage();
    await again.goto(link);
    await expect(again.getByText('Einladung ist ungültig oder abgelaufen').or(again.getByText(/ung.ltig oder abgelaufen/))).toBeVisible();
    await ctx.close();
  });

  test('an invitation for an existing account requires logging in with that account', async ({ page, playwright, world }) => {
    const admin = await adminLogin(playwright);
    const other = await createWorld(playwright, 'Zweite WG');
    const invite = await admin.ok(await admin.post(`/api/admin/wohngruppen/${other.groupId}/kassenverwalter-einladung`, { email: world.manager.email }));
    await page.goto(invite.link);
    await expect(page.getByText('Für diese Adresse gibt es schon ein Konto')).toBeVisible();
    await page.getByRole('link', { name: 'Anmelden' }).first().click();
    await expect(page).toHaveURL(/\/login\?next=%2Feinladung%2F/);
    await page.getByLabel('E-Mail').fill(world.manager.email);
    await page.getByLabel('Passwort', { exact: true }).fill(world.manager.password);
    await page.getByRole('button', { name: 'Anmelden' }).click();
    await expect(page.getByRole('button', { name: 'Einladung annehmen' })).toBeVisible();
    await page.getByRole('button', { name: 'Einladung annehmen' }).click();
    // jetzt gehört die Person zwei Gruppen an und wählt die aktive
    await expect(page.getByRole('heading', { name: 'Wohngruppe wählen' })).toBeVisible();
    await expect(page.getByRole('button', { name: new RegExp(world.groupName) })).toBeVisible();
    await expect(page.getByRole('button', { name: new RegExp(other.groupName) })).toBeVisible();
    await admin.dispose();
  });

  test('a manager cannot open the admin area', async ({ page, world }) => {
    await openAs(page, world.manager, '/admin');
    await expect(page.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
    await expect(page).not.toHaveURL(/\/admin/);
  });

  test('admin can open any group and switch between groups', async ({ page, playwright, world }) => {
    const admin = await adminLogin(playwright);
    const second = await createWorld(playwright, 'Zweite WG');
    await world.manager.api.setBalances(111, 1000);
    await second.manager.api.setBalances(222, 2000);

    await openAs(page, { name: ADMIN.name, email: ADMIN.email, password: ADMIN.password, api: admin }, '/');
    await expect(page.getByRole('heading', { name: 'Wohngruppen', exact: true })).toBeVisible();
    await page.getByRole('row', { name: new RegExp(world.groupName) }).getByRole('button', { name: 'Öffnen' }).click();
    await expect(page.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
    await expect(page.getByText(eur(111)).first()).toBeVisible();

    await pick(page, 'Wohngruppe', second.groupName);
    await expect(page.getByText(eur(222)).first()).toBeVisible();
    await expect(page.getByText(eur(111))).toHaveCount(0);

    await pick(page, 'Wohngruppe', world.groupName);
    await expect(page.getByText(eur(111)).first()).toBeVisible();
    await admin.dispose();
    await second.manager.api.dispose();
    await second.member.api.dispose();
  });

  test('group names must not be empty', async ({ page, playwright }) => {
    const admin = await adminLogin(playwright);
    await openAs(page, { name: ADMIN.name, email: ADMIN.email, password: ADMIN.password, api: admin }, '/admin');
    await page.getByRole('button', { name: 'Anlegen' }).click();
    await expect(page.getByText('Bitte einen Namen eingeben')).toBeVisible();
    await admin.dispose();
  });
});

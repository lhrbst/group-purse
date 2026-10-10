import { test, expect, openAs } from '../support/fixtures';

const MANAGER_ONLY = ['/pruefung', '/kasse', '/beitraege', '/auswertungen', '/mitglieder', '/einstellungen'];

test.describe('Rollen und Rechte', () => {
  test('a team member sees only the team navigation', async ({ page, world }) => {
    await openAs(page, world.member);
    await expect(page.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();
    const nav = page.locator('nav');
    for (const link of ['Start', 'Beleg erfassen', 'Meine Ausgaben']) {
      await expect(nav.getByRole('link', { name: link, exact: true })).toBeVisible();
    }
    for (const link of ['Prüfung', 'Kasse', 'Elternbeiträge', 'Auswertungen', 'Mitglieder', 'Einstellungen', 'Export', 'Wohngruppen (Admin)']) {
      await expect(nav.getByRole('link', { name: link, exact: true })).toHaveCount(0);
    }
  });

  test('a team member is sent home from every manager page', async ({ page, world }) => {
    await openAs(page, world.member);
    for (const path of [...MANAGER_ONLY, '/admin']) {
      await page.goto(path);
      await expect(page.getByRole('link', { name: 'Beleg erfassen', exact: true }).first(), path).toBeVisible();
      await expect(page).not.toHaveURL(new RegExp(path.replace('/', '\\/') + '$'));
    }
  });

  test('a manager sees the management navigation but not the admin area', async ({ page, world }) => {
    await openAs(page, world.manager);
    const nav = page.locator('nav');
    for (const link of ['Übersicht', 'Prüfung', 'Beleg erfassen', 'Kasse', 'Elternbeiträge', 'Auswertungen', 'Mitglieder', 'Einstellungen']) {
      await expect(nav.getByRole('link', { name: link, exact: true })).toBeVisible();
    }
    await expect(nav.getByRole('link', { name: 'Export', exact: true })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Wohngruppen (Admin)' })).toHaveCount(0);
  });

  test('the API enforces the same rules', async ({ world }) => {
    const forbiddenForMember = [
      ['GET', '/api/members'], ['GET', '/api/ledger'], ['GET', '/api/families'], ['GET', '/api/contributions/grid'],
      ['GET', '/api/dashboard/manager'], ['GET', '/api/expenses/pending'], ['GET', '/api/reports/income-vs-expense'], ['GET', '/api/admin/wohngruppen'],
    ] as const;
    for (const [, path] of forbiddenForMember) {
      expect((await world.member.api.get(path)).status(), path).toBe(403);
    }
    expect((await world.member.api.post('/api/ledger/anfangsbestand', { konto: 'BARKASSE', betrag: 1 })).status()).toBe(403);
    expect((await world.member.api.post('/api/families', { name: 'Hack' })).status()).toBe(403);
    expect((await world.member.api.put('/api/group/settings', { mindestbetragKasse: 1 })).status()).toBe(403);
    expect((await world.manager.api.get('/api/admin/wohngruppen')).status()).toBe(403);
    // Kassenstand dürfen beide sehen, das Vereinskonto nur der Kassenverwalter
    const forMember = await world.member.api.balance();
    expect(forMember.vereinskonto ?? null).toBeNull();
    expect((await world.manager.api.balance()).vereinskonto).toBe(0);
  });

  test('a team member cannot see the bank account figures on the home page', async ({ page, world }) => {
    await world.manager.api.setBalances(100, 4321);
    await openAs(page, world.member);
    await expect(page.getByText('Aktueller Kassenstand')).toBeVisible();
    await expect(page.getByText(/4\.321/)).toHaveCount(0);
    await expect(page.getByText('Vereinskonto')).toHaveCount(0);
  });
});

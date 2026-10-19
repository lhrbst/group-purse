import { test, expect, createWorld, openAs, PASSWORD } from '../support/fixtures';
import { Api } from '../support/api';
import { eur, loginViaUi, pick } from '../support/ui';

test.describe('Mandantentrennung: Wohngruppen sehen nichts voneinander', () => {
  test('a manager of one group cannot reach data of another group', async ({ playwright, world }) => {
    const other = await createWorld(playwright, 'Fremde WG');
    await world.manager.api.setBalances(100, 100);
    const expense = await world.member.api.manualExpense({ positions: [['Haushalt und Drogerie', 10]], haendler: 'Geheim' });
    const family = await world.manager.api.ok(await world.manager.api.post('/api/families', { name: 'Nur A', faelligkeitstag: 5, sollbetrag: 10, gueltigAb: '2026-01-01' }));
    await other.manager.api.setBalances(7, 8);

    const b = other.manager.api;
    expect(await (await b.get('/api/expenses/pending')).json()).toEqual([]);
    expect(await (await b.get('/api/families')).json()).toEqual([]);
    expect((await b.get(`/api/expenses/${expense.id}`)).status()).toBe(404);
    expect((await b.get(`/api/receipts/${expense.id}/0`)).status()).toBe(404);
    expect((await b.put(`/api/expenses/${expense.id}/details`, { datum: '2026-01-01', positionen: [{ kategorieId: await b.categoryId('Haushalt und Drogerie'), betrag: 1 }] })).status()).toBe(404);
    expect((await b.post(`/api/expenses/${expense.id}/approve`)).status()).toBe(404);
    expect((await b.post(`/api/ledger/${expense.id}/storno`, { begruendung: 'Hack' })).status()).toBe(400);
    expect((await b.put(`/api/families/${family.id}`, { name: 'Gehackt' })).status()).toBe(404);
    expect((await b.post('/api/contributions', { familieId: family.id, betrag: 10, monate: ['2026-01'] })).status()).toBe(404);
    expect(await b.balance()).toMatchObject({ barkasse: 7, vereinskonto: 8 });
    const ledger = (await (await b.get('/api/ledger')).json()) as { betrag: number }[];
    expect(ledger.map((x) => x.betrag).sort()).toEqual([7, 8]);

    // und die Daten von A sind unverändert
    expect(await world.manager.api.balance()).toMatchObject({ barkasse: 90, vereinskonto: 100 });
    for (const w of [other]) {
      await w.manager.api.dispose();
      await w.member.api.dispose();
    }
  });

  test('a member cannot switch into a foreign group, an admin can', async ({ playwright, world }) => {
    const other = await createWorld(playwright, 'Fremde WG');
    expect((await world.member.api.post('/api/me/active-group', { wohngruppeId: other.groupId })).status()).toBe(403);
    expect((await world.manager.api.post('/api/me/active-group', { wohngruppeId: other.groupId })).status()).toBe(403);
    expect((await world.manager.api.get('/api/members')).status()).toBe(200);
    expect(((await (await world.manager.api.get('/api/members')).json()) as { email: string }[]).map((m) => m.email)).not.toContain(other.member.email);
    await other.manager.api.dispose();
    await other.member.api.dispose();
  });

  test('screens only show the data of the active group', async ({ page, playwright, world }) => {
    const other = await createWorld(playwright, 'Fremde WG');
    await world.manager.api.setBalances(111, 1000);
    await other.manager.api.setBalances(222, 2000);
    await other.manager.api.ok(await other.manager.api.post('/api/families', { name: 'Fremdfamilie', faelligkeitstag: 5, sollbetrag: 10, gueltigAb: '2026-01-01' }));

    await openAs(page, world.manager, '/kasse');
    await expect(page.getByText(eur(111)).first()).toBeVisible();
    await expect(page.getByText(eur(222))).toHaveCount(0);
    await page.goto('/beitraege');
    await page.getByRole('tab', { name: 'Familien' }).click();
    await expect(page.getByText('Fremdfamilie')).toHaveCount(0);
    await page.goto('/mitglieder');
    await expect(page.getByText(other.member.email)).toHaveCount(0);
    await other.manager.api.dispose();
    await other.member.api.dispose();
  });

  test('one person can belong to several groups and switch between them', async ({ page, playwright, world }) => {
    const other = await createWorld(playwright, 'Zweite WG');
    await world.manager.api.setBalances(111, 0);
    await other.manager.api.setBalances(222, 0);

    // dieselbe Person registriert sich mit ihrem bestehenden Konto auch in der zweiten Gruppe
    const anon = await Api.anonymous(playwright);
    await anon.ok(await anon.post('/api/auth/register', { name: world.member.name, email: world.member.email, password: PASSWORD, beitrittscode: other.code, datenschutzZugestimmt: true }));
    const entries = await other.manager.api.ok<{ mitgliedschaftId: string; email: string }[]>(await other.manager.api.get('/api/members'));
    await other.manager.api.ok(await other.manager.api.post(`/api/members/${entries.find((m) => m.email === world.member.email)!.mitgliedschaftId}/freischalten`));
    await anon.dispose();

    await loginViaUi(page, world.member.email, PASSWORD);
    await expect(page.getByRole('heading', { name: 'Wohngruppe wählen' })).toBeVisible();
    await page.getByRole('button', { name: new RegExp(world.groupName) }).click();
    await expect(page.getByText(eur(111))).toBeVisible();

    await pick(page, 'Wohngruppe', other.groupName);
    await expect(page.getByText(eur(222))).toBeVisible();
    await expect(page.getByText(eur(111))).toHaveCount(0);
    await other.manager.api.dispose();
    await other.member.api.dispose();
  });

  test('expenses of one group are invisible to a member of another group with the same photo id', async ({ playwright, world }) => {
    const other = await createWorld(playwright, 'Fremde WG');
    const receipt = await world.member.api.capture(1);
    expect((await other.member.api.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(404);
    expect((await other.manager.api.get(`/api/receipts/${receipt.id}/0`)).status()).toBe(404);
    await other.manager.api.dispose();
    await other.member.api.dispose();
  });
});

import { test, expect, PASSWORD } from '../support/fixtures';
import { dialog, loginViaUi, logoutViaUi } from '../support/ui';
import { URLS } from '../support/ports';

test.describe('Anmelden, Abmelden, Registrieren', () => {
  test('wrong password shows an error and stays on the login page', async ({ page, world }) => {
    await loginViaUi(page, world.manager.email, 'ganz-falsches-passwort');
    await expect(page.getByText('E-Mail oder Passwort falsch')).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test('unknown e-mail gets the same message as a wrong password', async ({ page }) => {
    await loginViaUi(page, 'niemand@example.org', 'irgendwas-langes-123');
    await expect(page.getByText('E-Mail oder Passwort falsch')).toBeVisible();
  });

  test('manager logs in and out without any error message', async ({ page, world }) => {
    await loginViaUi(page, world.manager.email, world.manager.password);
    await expect(page.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
    await expect(page.getByText(world.groupName)).toBeVisible();

    await logoutViaUi(page);
    await expect(page.getByRole('heading', { name: 'Anmelden' })).toBeVisible();
    await expect(page.getByText('Das hat nicht geklappt')).toHaveCount(0);
    await expect(page.getByText('Bitte melde dich an')).toHaveCount(0);

    await page.goto('/kasse');
    await expect(page).toHaveURL(/\/login\?next=%2Fkasse/);
  });

  test('a protected page redirects to the login and returns there afterwards', async ({ page, world }) => {
    await page.goto('/kasse');
    await expect(page).toHaveURL(/\/login\?next=%2Fkasse/);
    await page.getByLabel('E-Mail').fill(world.manager.email);
    await page.getByLabel('Passwort', { exact: true }).fill(world.manager.password);
    await page.getByRole('button', { name: 'Anmelden' }).click();
    await expect(page).toHaveURL(/\/kasse$/);
    await expect(page.getByRole('heading', { name: 'Kasse', exact: true })).toBeVisible();
  });

  test('the next parameter cannot be abused as an open redirect', async ({ page, world }) => {
    await page.goto('/login?next=//evil.example.org');
    await page.getByLabel('E-Mail').fill(world.manager.email);
    await page.getByLabel('Passwort', { exact: true }).fill(world.manager.password);
    await page.getByRole('button', { name: 'Anmelden' }).click();
    await expect(page).toHaveURL(new RegExp(`^${URLS.web}/?$`));
  });

  test('a logged in user is sent away from the login page', async ({ page, world }) => {
    await loginViaUi(page, world.member.email, world.member.password);
    await expect(page.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();
    await page.goto('/login');
    await expect(page).not.toHaveURL(/\/login/);
  });

  test('registration needs a valid join code', async ({ page }) => {
    await page.goto('/registrieren');
    await page.getByLabel('Beitrittscode').fill('ZZZZZZZZ');
    await page.getByLabel('Name').fill('Tina Test');
    await page.getByLabel('E-Mail').fill(`falsch-${Date.now()}@example.org`);
    await page.getByLabel('Passwort', { exact: true }).fill(PASSWORD);
    await page.getByLabel('Ich habe den Datenschutzhinweis gelesen und stimme zu.').check();
    await page.getByRole('button', { name: 'Registrieren' }).click();
    await expect(page.getByText('Beitrittscode ungültig')).toBeVisible();
    await expect(page).toHaveURL(/\/registrieren/);
  });

  test('registration validates the form and requires the privacy consent', async ({ page, world }) => {
    await page.goto('/registrieren');
    await page.getByRole('button', { name: 'Registrieren' }).click();
    await expect(page.getByText('Bitte den Beitrittscode eingeben')).toBeVisible();
    await expect(page.getByText('Bitte Namen eingeben')).toBeVisible();
    await expect(page.getByText('Das Passwort braucht mindestens 10 Zeichen')).toBeVisible();
    await expect(page.getByText('Bitte den Datenschutzhinweis bestätigen')).toBeVisible();

    await page.getByRole('button', { name: 'Datenschutzhinweis lesen' }).click();
    await expect(dialog(page).getByText('Belegfotos können sensible Inhalte zeigen')).toBeVisible();
    await page.keyboard.press('Escape');
    expect(world.code).toHaveLength(8);
  });

  test('a new team member waits for approval, then gets access', async ({ page, world }) => {
    const email = `neu-${Date.now()}@example.org`;
    await page.goto('/registrieren');
    await page.getByLabel('Beitrittscode').fill(world.code.toLowerCase()); // Groß-/Kleinschreibung egal
    await page.getByLabel('Name').fill('Nina Neu');
    await page.getByLabel('E-Mail').fill(email);
    await page.getByLabel('Passwort', { exact: true }).fill(PASSWORD);
    await page.getByLabel('Ich habe den Datenschutzhinweis gelesen und stimme zu.').check();
    await page.getByRole('button', { name: 'Registrieren' }).click();

    await expect(page.getByText('Noch kein Zugriff')).toBeVisible();
    await expect(page.getByText(`${world.groupName}: wartend`)).toBeVisible();
    await expect(page.getByRole('link', { name: 'Beleg erfassen', exact: true })).toHaveCount(0);

    const members = await world.manager.api.ok<{ mitgliedschaftId: string; email: string }[]>(await world.manager.api.get('/api/members'));
    const entry = members.find((m) => m.email === email)!;
    await world.manager.api.ok(await world.manager.api.post(`/api/members/${entry.mitgliedschaftId}/freischalten`));

    await page.getByRole('button', { name: 'Status neu prüfen' }).click();
    await expect(page.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();
    await expect(page.getByText('Aktueller Kassenstand')).toBeVisible();
  });

  test('password reset works through a link created by the manager', async ({ page, browser, world }) => {
    await loginViaUi(page, world.manager.email, world.manager.password);
    await page.getByRole('link', { name: 'Mitglieder', exact: true }).click();
    const row = page.getByRole('row', { name: new RegExp(world.member.email) });
    await row.getByRole('button', { name: 'Passwort-Link' }).click();
    const link = (await dialog(page).getByText(/\/passwort-neu\//).textContent())!.trim();
    expect(link).toContain('/passwort-neu/');

    const memberCtx = await browser.newContext();
    const other = await memberCtx.newPage();
    await other.goto(link);
    await other.getByLabel('Neues Passwort').fill('Ein-ganz-neues-Passwort-9');
    await other.getByLabel('Wiederholen').fill('Ein-ganz-neues-Passwort-9');
    await other.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(other).toHaveURL(/\/login/);
    await expect(other.getByText('Passwort geändert')).toBeVisible();

    await loginViaUi(other, world.member.email, 'Ein-ganz-neues-Passwort-9');
    await expect(other.getByRole('link', { name: 'Beleg erfassen', exact: true }).first()).toBeVisible();

    // der Link ist nur einmal verwendbar und das alte Passwort gilt nicht mehr
    await other.context().clearCookies();
    await other.goto(link);
    await other.getByLabel('Neues Passwort').fill('Noch-ein-Passwort-123');
    await other.getByLabel('Wiederholen').fill('Noch-ein-Passwort-123');
    await other.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(other.getByText('Link ungültig oder abgelaufen')).toBeVisible();
    await memberCtx.close();

  });

  test('reset page rejects mismatching and short passwords', async ({ page }) => {
    await page.goto('/passwort-neu/irgendein-token');
    await page.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(page.getByText('Das Passwort braucht mindestens 10 Zeichen')).toBeVisible();
    await page.getByLabel('Neues Passwort').fill('ein-langes-passwort-1');
    await page.getByLabel('Wiederholen').fill('etwas-anderes-langes-2');
    await page.getByRole('button', { name: 'Passwort speichern' }).click();
    await expect(page.getByText('Die Passwörter stimmen nicht überein')).toBeVisible();
  });
});

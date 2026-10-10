import { test, expect, adminLogin, openAs } from '../support/fixtures';
import { ADMIN } from '../support/ports';
import type { Page } from '@playwright/test';

/** Jede Seite jeder Rolle muss laden, die erwartete Überschrift zeigen und darf keine Skriptfehler auslösen. */
function collectErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(`console: ${m.text()}`);
  });
  return errors;
}

const MANAGER_PAGES: [string, string][] = [
  ['/', 'Übersicht'], ['/pruefung', 'Prüfung'], ['/beleg', 'Beleg erfassen'], ['/beleg/ohne-foto', 'Beleg ohne Foto erfassen'],
  ['/kasse', 'Kasse'], ['/beitraege', 'Elternbeiträge'], ['/auswertungen', 'Auswertungen'], ['/mitglieder', 'Mitglieder'], ['/einstellungen', 'Einstellungen'],
];

test.describe('Smoke: alle Seiten laden', () => {
  test('manager pages', async ({ page, world }) => {
    const errors = collectErrors(page);
    await world.manager.api.setBalances(100, 500);
    await openAs(page, world.manager);
    for (const [path, heading] of MANAGER_PAGES) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: heading, exact: true }).first(), path).toBeVisible();
      await expect(page.getByText('Das hat nicht geklappt')).toHaveCount(0);
    }
    expect(errors).toEqual([]);
  });

  test('team member pages', async ({ page, world }) => {
    const errors = collectErrors(page);
    await openAs(page, world.member);
    for (const [path, heading] of [['/beleg', 'Beleg erfassen'], ['/meine-ausgaben', 'Meine Ausgaben'], ['/beleg/ohne-foto', 'Beleg ohne Foto erfassen']] as const) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: heading, exact: true }), path).toBeVisible();
    }
    await page.goto('/');
    await expect(page.getByText('Aktueller Kassenstand')).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('admin page and public pages', async ({ page, playwright }) => {
    const errors = collectErrors(page);
    for (const path of ['/login', '/registrieren']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'Gruppenkasse' })).toBeVisible();
    }
    await page.goto('/passwort-neu/xyz');
    await expect(page.getByRole('heading', { name: 'Neues Passwort setzen' })).toBeVisible();
    await page.goto('/einladung/gibt-es-nicht');
    await expect(page.getByText(/ung.ltig oder abgelaufen/)).toBeVisible();

    const admin = await adminLogin(playwright);
    await openAs(page, { name: ADMIN.name, email: ADMIN.email, password: ADMIN.password, api: admin }, '/admin');
    await expect(page.getByRole('heading', { name: 'Wohngruppen', exact: true })).toBeVisible();
    await admin.dispose();
    expect(errors).toEqual([]);
  });

  test('unknown routes fall back to the home page', async ({ page, world }) => {
    await openAs(page, world.manager, '/gibt-es-nicht/ueberhaupt');
    await expect(page.getByRole('heading', { name: 'Übersicht', exact: true })).toBeVisible();
  });
});

import { test, expect, createWorld, openAs } from '../support/fixtures';
import { pick, toast, toggleSwitch } from '../support/ui';
import type { Page } from '@playwright/test';

async function openCategoryChoices(page: Page) {
  await page.goto('/beleg/ohne-foto');
  await page.getByRole('combobox', { name: 'Kategorie Position 1', exact: true }).click();
}

test.describe('Kategorien und Unterkategorien', () => {
  test('the default list offers food subcategories and plain main categories, but not the food parent', async ({ page, world }) => {
    await openAs(page, world.member);
    await openCategoryChoices(page);
    for (const name of ['Lebensmittel › Obst und Gemüse', 'Lebensmittel › Brot und Backwaren', 'Lebensmittel › Milch, Käse und Eier',
      'Lebensmittel › Fleisch, Wurst und Fisch', 'Lebensmittel › Tiefkühl und Fertiggerichte', 'Lebensmittel › Grundnahrungsmittel',
      'Lebensmittel › Getränke', 'Lebensmittel › Süßes und Snacks', 'Lebensmittel › Aufstriche und Frühstück', 'Lebensmittel › Sonstige Lebensmittel',
      'Drogerie und Hygiene', 'Haushalt', 'Fahrten', 'Anschaffungen', 'Freizeit und Ausflüge', 'Geschenke und Feiern', 'Sonstiges']) {
      await expect(page.getByRole('option', { name, exact: true }), name).toBeVisible();
    }
    await expect(page.getByRole('option', { name: 'Lebensmittel', exact: true })).toHaveCount(0);
    await expect(page.getByRole('option', { name: /Pfand/ })).toHaveCount(0);
  });

  test('the settings page shows the hierarchy and lets the manager hide single subcategories', async ({ page, world }) => {
    await openAs(page, world.manager, '/einstellungen');
    await expect(page.getByText('Lebensmittel', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('Obst und Gemüse', { exact: true }).first()).toBeVisible();

    await toggleSwitch(page, 'Getränke verwenden', false);
    await page.reload();
    await expect(page.getByRole('switch', { name: 'Getränke verwenden', exact: true })).not.toBeChecked();

    await openAs(page, world.member);
    await openCategoryChoices(page);
    await expect(page.getByRole('option', { name: 'Lebensmittel › Getränke' })).toHaveCount(0);
    await expect(page.getByRole('option', { name: 'Lebensmittel › Obst und Gemüse' })).toBeVisible();
  });

  test('hiding the parent hides all of its subcategories', async ({ page, world }) => {
    await openAs(page, world.manager, '/einstellungen');
    await toggleSwitch(page, 'Lebensmittel verwenden', false);
    await openAs(page, world.member);
    await openCategoryChoices(page);
    await expect(page.getByRole('option', { name: /^Lebensmittel/ })).toHaveCount(0);
    await expect(page.getByRole('option', { name: 'Haushalt', exact: true })).toBeVisible();
  });

  test('own categories and subcategories can be added; a main category with children is no longer selectable itself', async ({ page, world }) => {
    await openAs(page, world.manager, '/einstellungen');
    await page.getByLabel('Eigene Kategorie hinzufügen').fill('Garten');
    await page.getByRole('button', { name: 'Hinzufügen' }).click();
    await toast(page, 'Kategorie angelegt.');
    await expect(page.getByText('Garten (eigene)')).toBeVisible();

    await page.getByLabel('Eigene Kategorie hinzufügen').fill('Putzmittel');
    await pick(page, 'Unterkategorie von', 'Haushalt');
    await page.getByRole('button', { name: 'Hinzufügen' }).click();
    await toast(page, 'Kategorie angelegt.');
    await expect(page.getByText('Putzmittel (eigene)')).toBeVisible();

    await openAs(page, world.member);
    await openCategoryChoices(page);
    await expect(page.getByRole('option', { name: 'Garten', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Haushalt › Putzmittel', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Haushalt', exact: true })).toHaveCount(0);
  });

  test('duplicate names are rejected', async ({ page, world }) => {
    await openAs(page, world.manager, '/einstellungen');
    await page.getByLabel('Eigene Kategorie hinzufügen').fill('haushalt');
    await page.getByRole('button', { name: 'Hinzufügen' }).click();
    await toast(page, 'Eine Kategorie mit diesem Namen existiert bereits');
  });

  test('team members cannot manage categories', async ({ page, world }) => {
    await openAs(page, world.member, '/einstellungen');
    await expect(page).not.toHaveURL(/einstellungen/);
    expect((await world.member.api.post('/api/categories', { name: 'Hack' })).status()).toBe(403);
    expect((await world.member.api.get('/api/categories?alle=true')).status()).toBe(403);
  });

  test('category settings are separate for every group', async ({ page, playwright, world }) => {
    const other = await createWorld(playwright, 'Andere WG');
    await openAs(page, world.manager, '/einstellungen');
    await toggleSwitch(page, 'Getränke verwenden', false);
    await page.getByLabel('Eigene Kategorie hinzufügen').fill('Nur hier');
    await page.getByRole('button', { name: 'Hinzufügen' }).click();
    await toast(page, 'Kategorie angelegt.');

    const names = (await (await other.manager.api.get('/api/categories')).json()).map((c: { name: string }) => c.name);
    expect(names).toContain('Getränke');
    expect(names).not.toContain('Nur hier');
    await other.manager.api.dispose();
    await other.member.api.dispose();
  });
});

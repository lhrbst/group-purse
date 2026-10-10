import { Locator, Page, expect } from '@playwright/test';

/** 1234.5 -> "1.234,50 €" (mit normalem Leerzeichen; Playwright normalisiert Leerraum beim Textvergleich) */
export function eur(n: number): string {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(n).replace(/ /g, ' ');
}

export async function loginViaUi(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('E-Mail').fill(email);
  await page.getByLabel('Passwort', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Anmelden' }).click();
}

export async function logoutViaUi(page: Page) {
  await page.getByLabel('Benutzermenü').click();
  await page.getByRole('menuitem', { name: 'Abmelden' }).click();
  await expect(page).toHaveURL(/\/login/);
}

/** Wählt in einer Mantine-Auswahl (Select) eine Option. */
export async function pick(page: Page, label: string, option: string | RegExp) {
  await page.getByRole('combobox', { name: label, exact: true }).click();
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click();
}

export function dialog(page: Page): Locator {
  return page.getByRole('dialog');
}

export async function toast(page: Page, text: string | RegExp) {
  await expect(page.getByText(text).first()).toBeVisible();
}

/** Navigation: bei schmalen Bildschirmen steckt sie hinter dem Burger-Menü. */
export async function goTo(page: Page, linkName: string) {
  const burger = page.getByLabel('Menü', { exact: true });
  if (await burger.isVisible()) {
    await burger.click();
  }
  await page.getByRole('link', { name: linkName, exact: true }).click();
}

/** Kamera-Eingabe der Foto-Auswahl (hat das Attribut capture). */
export const cameraInput = (page: Page) => page.locator('input[type=file][capture]');
/** Galerie-Eingabe (erlaubt mehrere Dateien). */
export const galleryInput = (page: Page) => page.locator('input[type=file][multiple]');

/** Mantine-Schalter: Der Zustand folgt erst der Server-Antwort, deshalb klicken und dann auf den neuen Zustand warten. */
export async function toggleSwitch(page: Page, name: string, expectChecked: boolean) {
  const sw = page.getByRole('switch', { name, exact: true });
  await sw.click({ force: true });
  if (expectChecked) await expect(sw).toBeChecked();
  else await expect(sw).not.toBeChecked();
}

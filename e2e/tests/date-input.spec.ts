import { test, expect, openAs } from '../support/fixtures';
import { dialog, toast } from '../support/ui';

const pad = (n: number) => String(n).padStart(2, '0');
const daysAgo = (n: number) => {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d;
};
const german = (d: Date) => `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()}`;
const digits6 = (d: Date) => `${pad(d.getDate())}${pad(d.getMonth() + 1)}${String(d.getFullYear()).slice(2)}`;

test.describe('Datumsfelder', () => {
  test('a typed short date is booked as that date', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    const target = daysAgo(3);
    await openAs(page, world.manager, '/kasse');
    await page.getByRole('button', { name: 'Rückgabe ans Konto' }).click();
    const date = dialog(page).getByLabel('Datum');
    await date.click();
    await date.press('Control+A');
    await date.pressSequentially(digits6(target));
    await dialog(page).getByLabel('Betrag (€)').fill('20');
    await dialog(page).getByLabel('Grund').fill('Datum getippt');
    await dialog(page).getByRole('button', { name: 'Rückgabe buchen' }).click();
    await toast(page, 'Rückgabe ans Konto gebucht.');
    await expect(dialog(page)).toBeHidden();
    // eine Rückgabe bucht zwei Zeilen (Barkasse und Vereinskonto), beide mit dem getippten Datum
    const day = german(target).replace(/\./g, '\\.');
    await expect(page.getByRole('row', { name: new RegExp(`Rückgabe.*${day}|${day}.*Rückgabe`) })).toHaveCount(2);
  });

  test('quick picks, the weekday and the allowed range work in the booking form', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await openAs(page, world.manager, '/kasse');
    await page.getByRole('button', { name: 'Rückgabe ans Konto' }).click();
    const date = dialog(page).getByLabel('Datum');

    await dialog(page).getByRole('button', { name: 'Gestern' }).click();
    await expect(date).toHaveValue(german(daysAgo(1)));
    await dialog(page).getByRole('button', { name: 'Heute' }).click();
    await expect(date).toHaveValue(german(new Date()));
    await expect(dialog(page).getByText(new Date().toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))).toBeVisible();

    // ein Datum vor dem erlaubten Bereich und eines in der Zukunft werden nicht übernommen, das Feld springt zurück
    for (const rejected of ['01.01.1900', german(daysAgo(-5))]) {
      await date.click();
      await date.press('Control+A');
      await date.pressSequentially(rejected);
      await date.press('Tab');
      await expect(date).toHaveValue(german(new Date()));
    }
  });

  test('the calendar opens in the month of the date, not in 1900', async ({ page, world }) => {
    await world.manager.api.setBalances(500, 0);
    await openAs(page, world.manager, '/kasse');
    await page.getByRole('button', { name: 'Rückgabe ans Konto' }).click();
    const date = dialog(page).getByLabel('Datum');
    await date.click();
    await date.press('Control+A');
    await date.pressSequentially('5');
    await expect(page.getByRole('button', { name: new RegExp(`${new Date().getFullYear()}`) }).first()).toBeVisible();
    // Kalender auf Deutsch: Monatsname und Wochentage, Woche beginnt am Montag
    const monthYear = new Date().toLocaleDateString('de-DE', { month: 'long', year: 'numeric' });
    await expect(page.getByRole('button', { name: monthYear, exact: true })).toBeVisible();
    const weekdays = page.getByRole('row').filter({ has: page.getByRole('columnheader', { name: 'Di', exact: true }) });
    await expect(weekdays.getByRole('columnheader').first()).toHaveText('Mo');
    await expect(weekdays.getByRole('columnheader')).toHaveCount(7);
    await expect(page.getByText('1900')).toHaveCount(0);
    await expect(page.getByText('2001')).toHaveCount(0);
  });
});

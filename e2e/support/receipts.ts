import { Page, expect } from '@playwright/test';
import { cameraInput, galleryInput, toast } from './ui';
import { photo } from './images';

/** Fotografiert über die Oberfläche einen Beleg und sendet ihn ab (eine oder mehrere Dateien). */
export async function captureViaUi(page: Page, files = [photo()]) {
  await page.goto('/beleg');
  await expect(page.getByRole('heading', { name: 'Beleg erfassen', exact: true })).toBeVisible();
  await (files.length > 1 ? galleryInput(page) : cameraInput(page)).setInputFiles(files);
  await expect(page.getByRole('img', { name: `Foto ${files.length}` })).toBeVisible();
  await page.getByRole('button', { name: 'Beleg absenden' }).click();
}

export async function captureAndWait(page: Page, files = [photo()]) {
  await captureViaUi(page, files);
  await toast(page, 'Beleg gesendet. Er wird jetzt verarbeitet.');
  await expect(page).toHaveURL(/\/$/);
}

export const LONG = { timeout: 40_000 };

import { defineConfig, devices } from '@playwright/test';
import { URLS } from './support/ports';

export default defineConfig({
  testDir: './tests',
  // Alle Tests teilen sich einen Stack (eine Datenbank, ein Backend); jeder Test legt seine eigene Wohngruppe an.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './support/global-setup.ts',
  use: {
    baseURL: URLS.web,
    locale: 'de-DE',
    timezoneId: 'Europe/Berlin',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
  },
  projects: [
    { name: 'desktop', testIgnore: /.*\.mobile\.spec\.ts/, use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', testMatch: /.*\.mobile\.spec\.ts/, use: { ...devices['Pixel 7'] } },
  ],
});

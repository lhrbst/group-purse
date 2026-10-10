import { test as base, expect, Page, PlaywrightWorkerArgs } from '@playwright/test';

type Playwright = PlaywrightWorkerArgs['playwright'];
import { Api } from './api';
import { waitForBackendIdle } from './db';
import { llm } from './llm';
import { ADMIN } from './ports';

export const PASSWORD = 'Passwort-e2e-12345';

export interface Person {
  name: string;
  email: string;
  password: string;
  api: Api;
}

export interface World {
  groupId: string;
  groupName: string;
  code: string;
  manager: Person;
  member: Person;
  /** Weiteres Teammitglied (freigeschaltet) in derselben Gruppe */
  addMember: (label?: string) => Promise<Person>;
}

let seq = 0;
const unique = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${(seq++).toString(36)}`;

export async function adminLogin(pw: Playwright): Promise<Api> {
  return Api.login(pw, ADMIN.email, ADMIN.password);
}

/** Legt eine Wohngruppe samt Kassenverwalter und freigeschaltetem Teammitglied an, über die API (die UI-Abläufe prüfen eigene Tests). */
export async function createWorld(pw: Playwright, label = 'WG'): Promise<World> {
  const admin = await adminLogin(pw);
  const groupName = `${label.replace(/\s+/g, ' ').trim()} ${unique('g')}`;
  const group = await admin.ok(await admin.post('/api/admin/wohngruppen', { name: groupName }), 'Wohngruppe anlegen');

  const managerEmail = `${unique('kassenverwalter')}@example.org`;
  const invite = await admin.ok(await admin.post(`/api/admin/wohngruppen/${group.id}/kassenverwalter-einladung`, { email: managerEmail }), 'Einladung');
  const token = String(invite.link).split('/').pop()!;
  const managerApi = await Api.anonymous(pw);
  const managerName = 'Karla Kassenverwalter';
  await managerApi.ok(await managerApi.post(`/api/einladungen/${token}/einloesen`, { name: managerName, password: PASSWORD, datenschutzZugestimmt: true }), 'Einladung annehmen');
  await admin.dispose();

  const world: World = {
    groupId: group.id,
    groupName,
    code: group.beitrittscode,
    manager: { name: managerName, email: managerEmail, password: PASSWORD, api: managerApi },
    member: undefined as unknown as Person,
    addMember: async (who = 'Teammitglied') => addMember(pw, world, who),
  };
  world.member = await addMember(pw, world, 'Mia Teammitglied');
  return world;
}

async function addMember(pw: Playwright, world: World, name: string): Promise<Person> {
  const email = `${unique('team')}@example.org`;
  const anon = await Api.anonymous(pw);
  await anon.ok(await anon.post('/api/auth/register', { name, email, password: PASSWORD, beitrittscode: world.code, datenschutzZugestimmt: true }), 'Registrieren');
  await anon.dispose();
  const members = await world.manager.api.ok<{ mitgliedschaftId: string; email: string }[]>(await world.manager.api.get('/api/members'), 'Mitglieder');
  const entry = members.find((m) => m.email === email)!;
  await world.manager.api.ok(await world.manager.api.post(`/api/members/${entry.mitgliedschaftId}/freischalten`), 'Freischalten');
  const api = await Api.login(pw, email, PASSWORD);
  return { name, email, password: PASSWORD, api };
}

/** Öffnet die App als diese Person, ohne Login-Formular (Sitzungs-Cookies aus dem API-Login). */
export async function openAs(page: Page, person: Person, path = '/') {
  await page.context().addCookies(await person.api.cookies());
  await page.goto(path);
}

export const test = base.extend<{ world: World; llm: typeof llm }>({
  llm: async ({}, use) => {
    await waitForBackendIdle();
    await llm.reset();
    await use(llm);
  },
  world: async ({ playwright }, use, testInfo) => {
    const world = await createWorld(playwright, testInfo.title.slice(0, 18).replace(/[^\wäöüÄÖÜß ]/g, ''));
    await use(world);
    await world.manager.api.dispose();
    await world.member.api.dispose();
  },
});

export { expect };

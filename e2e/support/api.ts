import { APIRequestContext, APIResponse, PlaywrightWorkerArgs, expect } from '@playwright/test';

type Playwright = PlaywrightWorkerArgs['playwright'];
import { URLS } from './ports';
import { makePng } from './images';

/** Dünner Client für die Backend-API mit Session und CSRF-Token, für schnelles Anlegen von Testdaten. */
export class Api {
  private constructor(readonly ctx: APIRequestContext) {}

  static async anonymous(pw: Playwright): Promise<Api> {
    return new Api(await pw.request.newContext({ baseURL: URLS.backend }));
  }

  static async login(pw: Playwright, email: string, password: string): Promise<Api> {
    const api = await Api.anonymous(pw);
    const res = await api.post('/api/auth/login', { email, password });
    expect(res.status(), `Login von ${email}`).toBe(200);
    return api;
  }

  async dispose() {
    await this.ctx.dispose();
  }

  async cookies() {
    return (await this.ctx.storageState()).cookies;
  }

  private async csrf(): Promise<string> {
    const find = async () => (await this.cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value;
    let token = await find();
    if (!token) {
      await this.ctx.get('/api/me');
      token = await find();
    }
    return token ?? '';
  }

  get(path: string): Promise<APIResponse> {
    return this.ctx.get(path);
  }

  async post(path: string, data?: unknown): Promise<APIResponse> {
    return this.ctx.post(path, { data, headers: { 'X-XSRF-TOKEN': await this.csrf() } });
  }

  async put(path: string, data?: unknown): Promise<APIResponse> {
    return this.ctx.put(path, { data, headers: { 'X-XSRF-TOKEN': await this.csrf() } });
  }

  async multipart(method: 'POST' | 'PUT', path: string, form: FormData): Promise<APIResponse> {
    return this.ctx.fetch(path, { method, multipart: form, headers: { 'X-XSRF-TOKEN': await this.csrf() } });
  }

  /** Erwartet Erfolg und liefert den JSON-Body. */
  async ok<T = any>(res: APIResponse, what = 'API-Aufruf'): Promise<T> {
    if (!res.ok()) throw new Error(`${what} fehlgeschlagen: ${res.status()} ${await res.text()}`);
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  // ---------- Kurzformen für häufige Aktionen

  async balance(): Promise<{ barkasse: number; vereinskonto?: number; warnung: boolean; negativ: boolean }> {
    return this.ok(await this.get('/api/balance'), 'Kassenstand');
  }

  async setBalances(barkasse: number, vereinskonto = 0) {
    await this.ok(await this.post('/api/ledger/anfangsbestand', { konto: 'BARKASSE', betrag: barkasse }), 'Anfangsbestand Barkasse');
    await this.ok(await this.post('/api/ledger/anfangsbestand', { konto: 'VEREINSKONTO', betrag: vereinskonto }), 'Anfangsbestand Vereinskonto');
  }

  async categoryId(name: string): Promise<string> {
    const list = await this.ok<{ id: string; name: string }[]>(await this.get('/api/categories'), 'Kategorien');
    const hit = list.find((c) => c.name === name);
    if (!hit) throw new Error(`Kategorie ${name} nicht gefunden`);
    return hit.id;
  }

  /** Beleg von Hand erfassen (verlässt sich nicht auf die KI). positions: [Kategoriename, Betrag][] */
  async manualExpense(opts: { positions: [string, number][]; datum?: string; haendler?: string; zweck?: string; withPhoto?: boolean; reason?: string }) {
    const positionen = [];
    for (const [name, betrag] of opts.positions) positionen.push({ kategorieId: await this.categoryId(name), betrag });
    const data = { datum: opts.datum ?? isoToday(), positionen, haendler: opts.haendler, zweck: opts.zweck, begruendungOhneBeleg: opts.reason };
    const form = new FormData();
    form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }), 'data.json');
    if (opts.withPhoto !== false) form.append('photos', new Blob([new Uint8Array(makePng(100, 100))], { type: 'image/png' }), 'beleg.png');
    return this.ok(await this.multipart('POST', '/api/expenses', form), 'Ausgabe erfassen');
  }

  /** Nur-Foto-Erfassung wie im Browser. Liefert den neuen Beleg. */
  async capture(photos = 1) {
    const form = new FormData();
    for (let i = 0; i < photos; i++) form.append('photos', new Blob([new Uint8Array(makePng(100 + i, 100))], { type: 'image/png' }), `beleg-${i}.png`);
    return this.ok(await this.multipart('POST', '/api/expenses/capture', form), 'Beleg erfassen');
  }
}

export function isoToday(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function isoMonth(offsetMonths = 0): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() + offsetMonths);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

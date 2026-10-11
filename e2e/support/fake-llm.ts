import http from 'node:http';

/**
 * Minimaler Ersatz für die OpenAI-API (POST /v1/chat/completions). So läuft die echte Belegverarbeitung im Backend
 * (Spring AI, Hintergrundjob, Wiederholungen, Timeout) ohne echtes Modell und deterministisch.
 * Gesteuert wird sie über POST /__control; die eingegangenen Anfragen liefert GET /__calls.
 */
export type Mode = 'success' | 'fail' | 'invalid' | 'mismatch' | 'slow' | 'unsure';

export interface LlmCall {
  model: string;
  images: number;
  /** MIME-Typ und Breite in Pixeln je Bild, aus den mitgeschickten Daten gelesen */
  imageMimes: string[];
  imageWidths: number[];
  categories: string[];
}

interface Receipt {
  /** Mehrere Bons auf einem Foto: Anzahl, Endsumme und Positionen gelten für alle zusammen */
  belegAnzahl?: number;
  sicher?: boolean;
  unsicherheit?: string;
  haendler: string;
  datum: string;
  gesamtbetrag: number;
  positionen: { kategorie: string; betrag: number }[];
}

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function defaultReceipt(): Receipt {
  return {
    haendler: 'REWE Testmarkt',
    datum: today(),
    gesamtbetrag: 23.4,
    positionen: [
      { kategorie: 'Obst und Gemüse', betrag: 8.4 },
      { kategorie: 'Milch, Käse und Eier', betrag: 6.2 },
      { kategorie: 'Drogerie und Hygiene', betrag: 8.8 },
    ],
  };
}

const state: { mode: Mode; receipt: Receipt; calls: LlmCall[]; slowMs: number } = {
  mode: 'success',
  receipt: defaultReceipt(),
  calls: [],
  slowMs: 6000,
};

function json(res: http.ServerResponse, status: number, body: unknown) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function completion(content: string) {
  return {
    id: 'chatcmpl-e2e',
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: 'fake-model',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
  };
}

export function imageWidth(b: Buffer, mime: string): number {
  try {
    if (mime === 'image/png') return b.readUInt32BE(16);
    if (mime === 'image/webp') {
      const fourcc = b.toString('ascii', 12, 16);
      if (fourcc === 'VP8 ') return b.readUInt16LE(26) & 0x3fff;
      if (fourcc === 'VP8L') return 1 + (((b[22] & 0x3f) << 8) | b[21]);
      if (fourcc === 'VP8X') return 1 + (b[24] | (b[25] << 8) | (b[26] << 16));
    }
    if (mime === 'image/jpeg') {
      let i = 2;
      while (i < b.length - 9) {
        if (b[i] !== 0xff) { i++; continue; }
        const marker = b[i + 1];
        if (marker >= 0xc0 && marker <= 0xc3) return b.readUInt16BE(i + 7);
        i += 2 + b.readUInt16BE(i + 2);
      }
    }
  } catch {
    // unlesbar: Breite unbekannt
  }
  return 0;
}

function describeRequest(raw: string): LlmCall {
  const body = JSON.parse(raw);
  let images = 0;
  const imageMimes: string[] = [];
  const imageWidths: number[] = [];
  let categories: string[] = [];
  for (const m of body.messages ?? []) {
    const parts = Array.isArray(m.content) ? m.content : [{ type: 'text', text: String(m.content ?? '') }];
    for (const part of parts) {
      if (part.type === 'image_url') {
        images++;
        const url = String(part.image_url?.url ?? '');
        const hit = url.match(/^data:([^;]+);base64,(.*)$/s);
        if (hit) {
          imageMimes.push(hit[1]);
          imageWidths.push(imageWidth(Buffer.from(hit[2], 'base64'), hit[1]));
        }
      }
      if (part.type === 'text' && typeof part.text === 'string') {
        const hit = part.text.match(/Erlaubte Kategorien: (.+)/);
        if (hit) categories = hit[1].split(',').map((s: string) => s.trim());
      }
    }
  }
  return { model: String(body.model ?? ''), images, imageMimes, imageWidths, categories };
}

function respond(res: http.ServerResponse) {
  const r = state.receipt;
  switch (state.mode) {
    case 'fail':
      return json(res, 500, { error: { message: 'Fake-KI: absichtlicher Fehler', type: 'server_error' } });
    case 'unsure':
      return json(res, 200, completion(JSON.stringify({ belegAnzahl: 3, sicher: false, unsicherheit: 'Der Endbetrag des mittleren Bons ist abgeschnitten.' })));
    case 'invalid':
      return json(res, 200, completion('Das ist leider kein gültiges JSON.'));
    case 'mismatch': {
      const broken = { ...r, positionen: r.positionen.map((p, i) => (i === 0 ? { ...p, betrag: p.betrag + 5 } : p)) };
      return json(res, 200, completion(JSON.stringify(broken)));
    }
    default:
      return json(res, 200, completion(JSON.stringify(r)));
  }
}

export function startFakeLlm(port: number): Promise<http.Server> {
  const server = http.createServer(async (req, res) => {
    try {
      const url = req.url ?? '';
      if (req.method === 'POST' && url === '/__control') {
        const c = JSON.parse((await readBody(req)) || '{}');
        if (c.reset) {
          state.mode = 'success';
          state.receipt = defaultReceipt();
          state.calls = [];
          state.slowMs = 6000;
        }
        if (c.mode) state.mode = c.mode;
        if (c.receipt) state.receipt = c.receipt;
        if (c.slowMs) state.slowMs = c.slowMs;
        return json(res, 200, { ok: true, mode: state.mode });
      }
      if (req.method === 'GET' && url === '/__calls') {
        return json(res, 200, state.calls);
      }
      if (req.method === 'POST' && url.endsWith('/chat/completions')) {
        const raw = await readBody(req);
        state.calls.push(describeRequest(raw));
        if (state.mode === 'slow') {
          await new Promise((r) => setTimeout(r, state.slowMs));
        }
        return respond(res);
      }
      return json(res, 404, { error: { message: `unbekannter Pfad ${req.method} ${url}` } });
    } catch (e) {
      return json(res, 500, { error: { message: String(e) } });
    }
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

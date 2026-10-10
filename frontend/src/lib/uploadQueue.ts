import { ApiError } from '../api/mutator';

/**
 * Lokale Warteschlange für Belegfotos (IndexedDB). Ein Foto geht nie verloren, auch wenn die Seite während des Uploads
 * geschlossen wird oder das Netz fehlt: Es bleibt hier liegen und wird automatisch nachgesendet.
 * Gespeichert werden Rohdaten (ArrayBuffer) statt Blobs, das ist in allen Browsern zuverlässiger.
 */
const DB_NAME = 'gruppenkasse';
const STORE = 'pending-receipts';

interface StoredPhoto {
  buffer: ArrayBuffer;
  type: string;
}

interface StoredReceipt {
  id: string;
  createdAt: number;
  photos: StoredPhoto[];
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE, { keyPath: 'id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function run<T>(mode: IDBTransactionMode, fn: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  try {
    return await new Promise<T>((resolve, reject) => {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  } finally {
    db.close();
  }
}

function toBuffer(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

export async function enqueue(photos: Blob[]): Promise<string> {
  const stored: StoredPhoto[] = [];
  for (const p of photos) stored.push({ buffer: await toBuffer(p), type: p.type || 'image/jpeg' });
  const receipt: StoredReceipt = { id: `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, createdAt: Date.now(), photos: stored };
  await run('readwrite', (s) => s.add(receipt));
  return receipt.id;
}

async function all(): Promise<StoredReceipt[]> {
  const list = await run<StoredReceipt[]>('readonly', (s) => s.getAll());
  return list.sort((a, b) => a.createdAt - b.createdAt);
}

export async function pendingCount(): Promise<number> {
  return run<number>('readonly', (s) => s.count());
}

async function remove(id: string): Promise<void> {
  await run('readwrite', (s) => s.delete(id));
}

/** Fehler, bei denen ein erneuter Versuch sinnlos ist (der Server hat den Beleg ausdrücklich abgelehnt). */
export function isPermanent(e: unknown): boolean {
  return e instanceof ApiError && e.status >= 400 && e.status < 500 && ![401, 408, 429].includes(e.status);
}

export interface FlushResult {
  sent: number;
  rejected: { message: string }[];
  remaining: number;
}

let flushing: Promise<FlushResult> | null = null;

/** Sendet alle wartenden Belege der Reihe nach. Bei Netz- oder Serverfehlern bleibt der Rest liegen und wird später versucht. */
export function flush(upload: (photos: Blob[]) => Promise<void>): Promise<FlushResult> {
  if (flushing) return flushing;
  flushing = (async () => {
    const result: FlushResult = { sent: 0, rejected: [], remaining: 0 };
    for (const receipt of await all()) {
      try {
        await upload(receipt.photos.map((p) => new Blob([p.buffer], { type: p.type })));
        await remove(receipt.id);
        result.sent++;
      } catch (e) {
        if (isPermanent(e)) {
          await remove(receipt.id);
          result.rejected.push({ message: e instanceof Error ? e.message : 'abgelehnt' });
        } else {
          break;
        }
      }
    }
    result.remaining = await pendingCount();
    return result;
  })().finally(() => {
    flushing = null;
  });
  return flushing;
}

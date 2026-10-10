import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '../api/mutator';
import { enqueue, flush, isPermanent, pendingCount } from './uploadQueue';

const photo = (text: string) => new Blob([text], { type: 'image/webp' });

async function clearQueue() {
  await flush(async () => undefined);
}

describe('uploadQueue', () => {
  beforeEach(async () => {
    await clearQueue();
  });

  it('keeps photos until they are uploaded, oldest first', async () => {
    await enqueue([photo('eins')]);
    await enqueue([photo('zwei'), photo('drei')]);
    expect(await pendingCount()).toBe(2);

    const seen: number[] = [];
    const result = await flush(async (photos) => {
      seen.push(photos.length);
    });
    expect(seen).toEqual([1, 2]);
    expect(result).toEqual({ sent: 2, rejected: [], remaining: 0 });
    expect(await pendingCount()).toBe(0);
  });

  it('restores the photo content and type', async () => {
    await enqueue([photo('inhalt')]);
    let restored: Blob | undefined;
    await flush(async (photos) => {
      restored = photos[0];
    });
    expect(restored?.type).toBe('image/webp');
    expect(restored?.size).toBe(6);
  });

  it('keeps everything and stops on network errors', async () => {
    await enqueue([photo('a')]);
    await enqueue([photo('b')]);
    let calls = 0;
    const result = await flush(async () => {
      calls++;
      throw new TypeError('Failed to fetch');
    });
    expect(calls).toBe(1);
    expect(result.sent).toBe(0);
    expect(result.remaining).toBe(2);
    expect((await flush(async () => undefined)).sent).toBe(2);
  });

  it('keeps photos when the session expired or the server is down', async () => {
    await enqueue([photo('a')]);
    for (const status of [401, 429, 500, 503]) {
      const r = await flush(async () => {
        throw new ApiError(status, 'x');
      });
      expect(r.remaining).toBe(1);
    }
    await clearQueue();
  });

  it('drops photos the server rejected for good and reports them', async () => {
    await enqueue([photo('kaputt')]);
    const result = await flush(async () => {
      throw new ApiError(400, 'Nur Fotos sind erlaubt');
    });
    expect(result.sent).toBe(0);
    expect(result.rejected).toEqual([{ message: 'Nur Fotos sind erlaubt' }]);
    expect(result.remaining).toBe(0);
  });

  it('classifies errors', () => {
    expect(isPermanent(new ApiError(400, ''))).toBe(true);
    expect(isPermanent(new ApiError(403, ''))).toBe(true);
    expect(isPermanent(new ApiError(401, ''))).toBe(false);
    expect(isPermanent(new ApiError(500, ''))).toBe(false);
    expect(isPermanent(new TypeError('offline'))).toBe(false);
  });

  it('does not run two flushes at once', async () => {
    await enqueue([photo('a')]);
    let uploads = 0;
    const slow = async () => {
      uploads++;
      await new Promise((r) => setTimeout(r, 20));
    };
    const [r1, r2] = await Promise.all([flush(slow), flush(slow)]);
    expect(uploads).toBe(1);
    expect(r1).toBe(r2);
  });
});

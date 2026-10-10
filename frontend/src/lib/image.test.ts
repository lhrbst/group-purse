import { describe, expect, it, vi } from 'vitest';
import { resizeImage, targetSize } from './image';

describe('targetSize', () => {
  it('scales wide photos down keeping the ratio', () => {
    expect(targetSize(3000, 2000, 1500)).toEqual({ width: 1500, height: 1000 });
  });

  it('never enlarges small photos', () => {
    expect(targetSize(1000, 800, 1500)).toEqual({ width: 1000, height: 800 });
  });
});

describe('resizeImage', () => {
  function mockCanvas(webpSupported: boolean) {
    const drawn: number[] = [];
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage: (_b: unknown, _x: number, _y: number, w: number, h: number) => drawn.push(w, h) }),
      toBlob: (cb: (b: Blob | null) => void, type: string) => {
        if (type === 'image/webp') cb(new Blob(['x'], { type: webpSupported ? 'image/webp' : 'image/png' }));
        else cb(new Blob(['x'], { type }));
      },
    };
    vi.spyOn(document, 'createElement').mockReturnValue(canvas as unknown as HTMLElement);
    vi.stubGlobal('createImageBitmap', async () => ({ width: 3000, height: 2000, close: () => undefined }));
    return { canvas, drawn };
  }

  it('draws at most 1500px wide and prefers webp', async () => {
    const { drawn } = mockCanvas(true);
    const out = await resizeImage(new Blob(['foto']));
    expect(drawn).toEqual([1500, 1000]);
    expect(out.type).toBe('image/webp');
  });

  it('falls back to jpeg when the browser cannot encode webp', async () => {
    mockCanvas(false);
    const out = await resizeImage(new Blob(['foto']));
    expect(out.type).toBe('image/jpeg');
  });
});

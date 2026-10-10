/** Berechnet die Zielgröße: nur verkleinern, nie vergrößern. */
export function targetSize(width: number, height: number, maxWidth: number): { width: number; height: number } {
  if (width <= maxWidth) return { width, height };
  const scale = maxWidth / width;
  return { width: maxWidth, height: Math.round(height * scale) };
}

async function toBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

/**
 * Verkleinert ein Foto im Browser auf maximal maxWidth Pixel Breite. WebP, wenn der Browser es erzeugen kann
 * (iOS Safari liefert stattdessen PNG), sonst JPEG.
 */
export async function resizeImage(file: Blob, maxWidth = 1500): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const { width, height } = targetSize(bitmap.width, bitmap.height, maxWidth);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Das Foto konnte nicht verarbeitet werden.');
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();
  const webp = await toBlob(canvas, 'image/webp', 0.8);
  if (webp && webp.type === 'image/webp') return webp;
  const jpeg = await toBlob(canvas, 'image/jpeg', 0.82);
  if (!jpeg) throw new Error('Das Foto konnte nicht verarbeitet werden.');
  return jpeg;
}

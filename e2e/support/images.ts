import { deflateSync, crc32 } from 'node:zlib';

function chunk(type: string, data: Buffer): Buffer {
  const t = Buffer.from(type, 'ascii');
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])) >>> 0);
  return Buffer.concat([len, t, data, crc]);
}

/** Gültiges PNG in einer Farbe, mit einfachem Verlauf, damit Verkleinern und Kodieren im Browser etwas zu tun haben. */
export function makePng(width = 640, height = 480, color: [number, number, number] = [220, 220, 220]): Buffer {
  const rows: Buffer[] = [];
  for (let y = 0; y < height; y++) {
    const row = Buffer.alloc(1 + width * 3);
    for (let x = 0; x < width; x++) {
      const shade = Math.round((x / width) * 30);
      row[1 + x * 3] = Math.max(0, color[0] - shade);
      row[2 + x * 3] = Math.max(0, color[1] - shade);
      row[3 + x * 3] = Math.max(0, color[2] - shade);
    }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(Buffer.concat(rows))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

let counter = 0;

/** Datei für setInputFiles; jeder Aufruf liefert ein anderes Bild. */
export function photo(width = 640, height = 480) {
  counter++;
  return { name: `beleg-${counter}.png`, mimeType: 'image/png', buffer: makePng(width, height, [200 + (counter % 50), 210, 220 - (counter % 40)]) };
}

// Erzeugt die PWA-Icons (blaue Fläche mit weißer Münze) ohne externe Abhängigkeiten.
import { deflateSync, crc32 } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';

function chunk(type, data) {
  const t = Buffer.from(type, 'ascii');
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])) >>> 0);
  return Buffer.concat([len, t, data, crc]);
}

function png(size) {
  const bg = [0x1c, 0x7e, 0xd6];
  const fg = [255, 255, 255];
  const rows = [];
  const c = (size - 1) / 2;
  for (let y = 0; y < size; y++) {
    const row = Buffer.alloc(1 + size * 3);
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x - c, y - c) / size;
      let color = bg;
      if (d < 0.3 && d > 0.22) color = fg; // Münzrand
      else if (d <= 0.22 && Math.abs(y - c) < size * 0.035 && Math.abs(x - c) < size * 0.14) color = fg; // Querstrich
      else if (d <= 0.22 && Math.abs(x - c) < size * 0.035 && Math.abs(y - c) < size * 0.14) color = fg; // Längsstrich
      row.set(color, 1 + x * 3);
    }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // Bit-Tiefe
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(Buffer.concat(rows))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync('public/icons', { recursive: true });
writeFileSync('public/icons/icon-192.png', png(192));
writeFileSync('public/icons/icon-512.png', png(512));
console.log('Icons geschrieben');

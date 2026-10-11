const euro = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' });

export function formatEuro(value: number | null | undefined): string {
  return euro.format(value ?? 0);
}

/** 2026-03-14 -> 14.03.2026 */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const [y, m, d] = iso.substring(0, 10).split('-');
  return `${d}.${m}.${y}`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' });
}

const MONTHS = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];

/** 2026-03 -> Mär 2026 */
export function formatMonth(ym: string): string {
  const [y, m] = ym.split('-');
  return `${MONTHS[Number(m) - 1]} ${y}`;
}

const MONTHS_LONG = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

/** 2026-03 -> März 2026 */
export function formatMonthLong(ym: string): string {
  const [y, m] = ym.split('-');
  return `${MONTHS_LONG[Number(m) - 1]} ${y}`;
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function currentMonth(): string {
  return todayIso().substring(0, 7);
}

export function shiftMonth(ym: string, delta: number): string {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Eingabe wie "12,50" oder "12.50" in eine Zahl umwandeln; NaN bei Unsinn. */
export function parseAmount(input: string | number): number {
  if (typeof input === 'number') return input;
  const cleaned = input.trim().replace(/\s/g, '').replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.');
  return cleaned === '' ? NaN : Number(cleaned);
}

/** Die letzten count Monate, der laufende zuerst. */
export function recentMonths(count: number): string[] {
  const now = currentMonth();
  return Array.from({ length: count }, (_, i) => shiftMonth(now, -i));
}

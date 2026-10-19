const pad = (n: number) => String(n).padStart(2, '0');

/** Baut "YYYY-MM-DD" nur für echte Kalendertage (kein 31.04., kein 29.02. im Nichtschaltjahr, kein Jahr unter 1000). */
function build(y: number, m: number, d: number): string | null {
  if (y < 1000) return null;
  const date = new Date(y, m - 1, d);
  if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d) return null;
  return `${y}-${pad(m)}-${pad(d)}`;
}

/**
 * Liest eine getippte Datumseingabe großzügig: 11.10.2026, 11.10.26, 11.10., 1110, 111026, 11102026, 2026-10-11,
 * dazu "heute", "gestern" und "vorgestern". Zweistellige Jahre zählen ab 2000. Unvollständiges oder Unsinniges ergibt null,
 * damit ein halb getipptes Datum den Wert nie auf ein falsches Jahr springen lässt.
 */
export function parseGermanDate(input: string, today: Date = new Date()): string | null {
  const text = input.trim().toLowerCase();
  if (!text) return null;

  const daysAgo = (n: number) => {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - n);
    return build(d.getFullYear(), d.getMonth() + 1, d.getDate());
  };
  if (text === 'heute') return daysAgo(0);
  if (text === 'gestern') return daysAgo(1);
  if (text === 'vorgestern') return daysAgo(2);

  const thisYear = today.getFullYear();
  if (/^\d+$/.test(text)) {
    const d = Number(text.slice(0, 2));
    const m = Number(text.slice(2, 4));
    if (text.length === 4) return build(thisYear, m, d);
    if (text.length === 6) return build(2000 + Number(text.slice(4)), m, d);
    if (text.length === 8) return build(Number(text.slice(4)), m, d);
    return null;
  }

  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(text);
  if (iso) return build(Number(iso[1]), Number(iso[2]), Number(iso[3]));

  const parts = /^(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2}|\d{4})?)?$/.exec(text);
  if (!parts) return null;
  const year = parts[3] === undefined ? thisYear : parts[3].length === 2 ? 2000 + Number(parts[3]) : Number(parts[3]);
  return build(year, Number(parts[2]), Number(parts[1]));
}

/** Nur vollständige Eingaben, die nicht mehr zu einem längeren Datum anwachsen können (vierstelliges Jahr, ISO, 8 Ziffern, Wörter). */
const COMPLETE = /^(?:\d{1,2}[./-]\d{1,2}[./-]\d{4}|\d{8}|\d{4}-\d{1,2}-\d{1,2}|heute|gestern|vorgestern)$/i;

/**
 * Für den Wert während des Tippens: übernimmt nur vollständige Daten. Sonst würde "11.10.20" auf dem Weg zu "11.10.2026"
 * schon als 2020 gelten und bei einer abgelehnten Endeingabe stehen bleiben. Kurzformen wertet das Feld beim Verlassen aus.
 */
export function parseWhileTyping(input: string, today: Date = new Date()): string | null {
  return COMPLETE.test(input.trim()) ? parseGermanDate(input, today) : null;
}

/** Liegt das ISO-Datum zwischen den Grenzen (beide optional, jeweils eingeschlossen)? */
export function isWithin(iso: string, min?: string | null, max?: string | null): boolean {
  return (!min || iso >= min) && (!max || iso <= max);
}

/** Frühestes sinnvolles Datum: 1. Januar vor zehn Jahren (Aufbewahrungsfrist). */
export function minDateIso(today: Date = new Date()): string {
  return `${today.getFullYear() - 10}-01-01`;
}

/** 2026-10-11 -> Sonntag, 11. Oktober 2026 */
export function formatLongDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const [y, m, d] = iso.substring(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

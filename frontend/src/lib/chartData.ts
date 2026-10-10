import type { CategoryMonthSum, IncomeExpense } from '../api/model';
import { formatMonth } from './format';

export const SERIES_COLORS = ['blue.6', 'teal.6', 'grape.6', 'orange.6', 'cyan.6', 'pink.6', 'lime.7', 'indigo.6', 'red.6', 'yellow.7'];

/** Macht aus (Monat, Kategorie, Summe)-Zeilen eine Tabelle je Monat mit einer Spalte je Kategorie für gestapelte Balken. */
export function pivotByMonth(rows: CategoryMonthSum[]) {
  const categories = [...new Set(rows.map((r) => r.kategorie ?? ''))].sort((a, b) => a.localeCompare(b, 'de'));
  const months = [...new Set(rows.map((r) => r.monat ?? ''))].sort();
  const data = months.map((m) => {
    const row: Record<string, string | number> = { monat: formatMonth(m) };
    for (const c of categories) {
      row[c] = rows.filter((r) => r.monat === m && r.kategorie === c).reduce((s, r) => s + (r.summe ?? 0), 0);
    }
    return row;
  });
  const series = categories.map((name, i) => ({ name, color: SERIES_COLORS[i % SERIES_COLORS.length] }));
  return { data, series };
}

export function incomeExpenseData(rows: IncomeExpense[]) {
  return rows.map((r) => ({ monat: formatMonth(r.monat ?? ''), Einnahmen: r.einnahmen ?? 0, Ausgaben: r.ausgaben ?? 0 }));
}

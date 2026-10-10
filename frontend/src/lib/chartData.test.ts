import { describe, expect, it } from 'vitest';
import { incomeExpenseData, pivotByMonth } from './chartData';

describe('pivotByMonth', () => {
  it('builds one row per month and one series per category', () => {
    const { data, series } = pivotByMonth([
      { monat: '2026-01', kategorie: 'Lebensmittel', summe: 20 },
      { monat: '2026-01', kategorie: 'Haushalt', summe: 5 },
      { monat: '2026-02', kategorie: 'Lebensmittel', summe: 30 },
    ]);
    expect(series.map((s) => s.name)).toEqual(['Haushalt', 'Lebensmittel']);
    expect(data).toEqual([
      { monat: 'Jan 2026', Haushalt: 5, Lebensmittel: 20 },
      { monat: 'Feb 2026', Haushalt: 0, Lebensmittel: 30 },
    ]);
  });

  it('copes with no data', () => {
    expect(pivotByMonth([])).toEqual({ data: [], series: [] });
  });
});

describe('incomeExpenseData', () => {
  it('maps to chart rows with German month labels', () => {
    expect(incomeExpenseData([{ monat: '2026-03', einnahmen: 100, ausgaben: 40 }])).toEqual([{ monat: 'Mär 2026', Einnahmen: 100, Ausgaben: 40 }]);
  });
});

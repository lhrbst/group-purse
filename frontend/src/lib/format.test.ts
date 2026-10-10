import { describe, expect, it } from 'vitest';
import { formatDate, formatEuro, formatMonth, parseAmount, shiftMonth } from './format';

describe('format', () => {
  it('formats euro the German way', () => {
    expect(formatEuro(1234.5).replace(/ /g, ' ')).toBe('1.234,50 €');
    expect(formatEuro(0).replace(/ /g, ' ')).toBe('0,00 €');
    expect(formatEuro(-5).replace(/ /g, ' ')).toContain('5,00');
  });

  it('formats dates and months', () => {
    expect(formatDate('2026-03-14')).toBe('14.03.2026');
    expect(formatDate('2026-03-14T10:00:00Z')).toBe('14.03.2026');
    expect(formatDate(null)).toBe('');
    expect(formatMonth('2026-03')).toBe('Mär 2026');
  });

  it('shifts months across year boundaries', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(shiftMonth('2026-11', 3)).toBe('2027-02');
  });

  it('parses German and English amounts', () => {
    expect(parseAmount('12,50')).toBe(12.5);
    expect(parseAmount('12.50')).toBe(12.5);
    expect(parseAmount('1.234,50')).toBe(1234.5);
    expect(parseAmount(' 7 ')).toBe(7);
    expect(parseAmount('abc')).toBeNaN();
    expect(parseAmount('')).toBeNaN();
  });
});

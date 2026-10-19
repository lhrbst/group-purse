import { describe, expect, it } from 'vitest';
import { formatLongDate, isWithin, minDateIso, parseGermanDate, parseWhileTyping } from './dateInput';

const TODAY = new Date(2026, 9, 19); // Montag, 19.10.2026

const parse = (s: string) => parseGermanDate(s, TODAY);

describe('parseGermanDate', () => {
  it('reads the usual German forms', () => {
    expect(parse('11.10.2026')).toBe('2026-10-11');
    expect(parse('1.2.2026')).toBe('2026-02-01');
    expect(parse('11/10/2026')).toBe('2026-10-11');
    expect(parse('11-10-2026')).toBe('2026-10-11');
    expect(parse(' 11.10.2026 ')).toBe('2026-10-11');
  });

  it('completes missing parts', () => {
    expect(parse('11.10.')).toBe('2026-10-11');
    expect(parse('11.10')).toBe('2026-10-11');
    expect(parse('11.10.26')).toBe('2026-10-11');
    expect(parse('3.3.99')).toBe('2099-03-03');
  });

  it('reads digits without separators', () => {
    expect(parse('1110')).toBe('2026-10-11');
    expect(parse('111026')).toBe('2026-10-11');
    expect(parse('11102026')).toBe('2026-10-11');
  });

  it('reads ISO dates and the words for today and yesterday', () => {
    expect(parse('2026-10-11')).toBe('2026-10-11');
    expect(parse('heute')).toBe('2026-10-19');
    expect(parse('Gestern')).toBe('2026-10-18');
    expect(parse('vorgestern')).toBe('2026-10-17');
  });

  it('counts back across month and year ends', () => {
    expect(parseGermanDate('gestern', new Date(2026, 0, 1))).toBe('2025-12-31');
    expect(parseGermanDate('vorgestern', new Date(2026, 2, 1))).toBe('2026-02-27');
  });

  it('accepts real dates only', () => {
    expect(parse('29.02.2028')).toBe('2028-02-29');
    expect(parse('29.02.2026')).toBeNull();
    expect(parse('31.04.2026')).toBeNull();
    expect(parse('32.01.2026')).toBeNull();
    expect(parse('11.13.2026')).toBeNull();
    expect(parse('0.1.2026')).toBeNull();
  });

  it('gives nothing for unfinished or nonsensical input', () => {
    for (const s of ['', ' ', '1', '11', '11.', '19', '2026', '11.10.2', '11.10.202', '11.10.20266', 'abc', '11.10.2026x', '11..2026', '1.1.19.5', '00000000']) {
      expect(parse(s), `"${s}"`).toBeNull();
    }
  });

  it('never invents a year before 1000', () => {
    expect(parse('11.10.0099')).toBeNull();
  });
});

describe('parseWhileTyping', () => {
  const live = (s: string) => parseWhileTyping(s, TODAY);

  it('takes only complete dates, so half-typed text never commits a wrong year', () => {
    expect(live('11.10.2026')).toBe('2026-10-11');
    expect(live('1.2.2026')).toBe('2026-02-01');
    expect(live('11102026')).toBe('2026-10-11');
    expect(live('2026-10-11')).toBe('2026-10-11');
    expect(live('heute')).toBe('2026-10-19');
    expect(live('gestern')).toBe('2026-10-18');
  });

  it('waits for the rest of anything that could still grow into a longer entry', () => {
    for (const s of ['1110', '11.10', '11.10.', '11.10.2', '11.10.20', '11.10.26', '111026', '11.10.202']) {
      expect(live(s), `"${s}"`).toBeNull();
    }
  });
});

describe('isWithin', () => {
  it('compares against both bounds, which may be missing', () => {
    expect(isWithin('2026-10-11', '2016-01-01', '2026-10-19')).toBe(true);
    expect(isWithin('2026-10-20', '2016-01-01', '2026-10-19')).toBe(false);
    expect(isWithin('1900-01-01', '2016-01-01', undefined)).toBe(false);
    expect(isWithin('2099-01-01', undefined, undefined)).toBe(true);
    expect(isWithin('2026-10-19', '2016-01-01', '2026-10-19')).toBe(true);
  });
});

describe('minDateIso', () => {
  it('is the first of January ten years back', () => {
    expect(minDateIso(TODAY)).toBe('2016-01-01');
  });
});

describe('formatLongDate', () => {
  it('names the weekday', () => {
    expect(formatLongDate('2026-10-11')).toBe('Sonntag, 11. Oktober 2026');
    expect(formatLongDate('2028-02-29')).toBe('Dienstag, 29. Februar 2028');
  });

  it('is empty for nothing', () => {
    expect(formatLongDate(null)).toBe('');
    expect(formatLongDate('')).toBe('');
  });
});

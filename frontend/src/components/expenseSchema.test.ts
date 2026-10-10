import { describe, expect, it } from 'vitest';
import { expenseSchema, missingReceiptError, totalOf } from './expenseSchema';
import { todayIso } from '../lib/format';

const pos = (kategorieId: string, betrag: string) => ({ key: Math.random().toString(), kategorieId, betrag });
const base = {
  datum: todayIso(),
  positionen: [pos('obst', '8,40'), pos('haushalt', '4,99')],
  haendler: '',
  zweck: '',
  begruendungOhneBeleg: '',
};

describe('expenseSchema', () => {
  it('accepts an expense with several positions', () => {
    expect(expenseSchema.safeParse(base).success).toBe(true);
  });

  it('needs at least one position', () => {
    expect(expenseSchema.safeParse({ ...base, positionen: [] }).success).toBe(false);
  });

  it('every position needs a category', () => {
    const r = expenseSchema.safeParse({ ...base, positionen: [pos('', '5,00')] });
    expect(r.success).toBe(false);
  });

  it('rejects zero, negative, unreadable and too precise amounts', () => {
    for (const betrag of ['0', '-3', 'abc', '', '1,234']) {
      expect(expenseSchema.safeParse({ ...base, positionen: [pos('obst', betrag)] }).success).toBe(false);
    }
  });

  it('rejects future dates', () => {
    expect(expenseSchema.safeParse({ ...base, datum: '2999-01-01' }).success).toBe(false);
  });
});

describe('totalOf', () => {
  it('sums German and English amounts rounded to cents', () => {
    expect(totalOf([{ betrag: '8,40' }, { betrag: '4.99' }, { betrag: '0,1' }, { betrag: '0,2' }])).toBe(13.69);
  });

  it('ignores unreadable entries', () => {
    expect(totalOf([{ betrag: '' }, { betrag: 'x' }, { betrag: '3' }])).toBe(3);
  });
});

describe('missingReceiptError', () => {
  it('needs a reason when there is no photo', () => {
    expect(missingReceiptError(0, 0, '')).toMatch(/Begründung/);
    expect(missingReceiptError(0, 0, 'Bon verloren')).toBeNull();
  });

  it('is fine with new or existing photos', () => {
    expect(missingReceiptError(1, 0, '')).toBeNull();
    expect(missingReceiptError(0, 2, '')).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import { expenseSchema, missingReceiptError } from './expenseSchema';
import { todayIso } from '../lib/format';

const base = { betrag: '12,50', datum: todayIso(), kategorieId: 'abc', haendler: '', zweck: '', bewohnerKuerzel: '', begruendungOhneBeleg: '' };

describe('expenseSchema', () => {
  it('accepts a valid expense', () => {
    expect(expenseSchema.safeParse(base).success).toBe(true);
  });

  it('rejects zero, negative and unreadable amounts', () => {
    for (const betrag of ['0', '-3', 'abc', '']) {
      expect(expenseSchema.safeParse({ ...base, betrag }).success).toBe(false);
    }
  });

  it('rejects more than two decimals', () => {
    expect(expenseSchema.safeParse({ ...base, betrag: '1,234' }).success).toBe(false);
  });

  it('rejects future dates and missing category', () => {
    expect(expenseSchema.safeParse({ ...base, datum: '2999-01-01' }).success).toBe(false);
    expect(expenseSchema.safeParse({ ...base, kategorieId: '' }).success).toBe(false);
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

import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { zodValidate } from './zodForm';

describe('zodValidate', () => {
  const schema = z.object({
    email: z.string().min(1, 'E-Mail fehlt'),
    password: z.string().min(10, 'Mindestens 10 Zeichen'),
  });

  it('returns no errors for valid input', () => {
    expect(zodValidate(schema)({ email: 'a@b.de', password: '1234567890' })).toEqual({});
  });

  it('maps issues to field errors, first message wins', () => {
    expect(zodValidate(schema)({ email: '', password: 'kurz' })).toEqual({
      email: 'E-Mail fehlt',
      password: 'Mindestens 10 Zeichen',
    });
  });
});

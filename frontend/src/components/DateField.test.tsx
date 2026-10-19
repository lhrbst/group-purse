import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DateField } from './DateField';
import { renderWithProviders } from '../test/utils';

function Harness({ initial = null, ...props }: { initial?: string | null; maxDate?: string; quickPicks?: boolean; compact?: boolean }) {
  const [value, setValue] = useState<string | null>(initial);
  return <DateField label="Datum" value={value} onChange={setValue} {...props} />;
}

describe('DateField', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 19)); // Montag, 19.10.2026
  });
  afterEach(() => vi.useRealTimers());

  const input = () => screen.getByLabelText('Datum') as HTMLInputElement;

  it('shows the German placeholder', () => {
    renderWithProviders(<Harness />);
    expect(input()).toHaveAttribute('placeholder', 'TT.MM.JJJJ');
  });

  it('takes a typed German date and names the weekday', async () => {
    renderWithProviders(<Harness />);
    await userEvent.type(input(), '11.10.2026');
    expect(input().value).toBe('11.10.2026');
    expect(screen.getByText('Sonntag, 11. Oktober 2026')).toBeInTheDocument();
  });

  it('understands short forms and words', async () => {
    renderWithProviders(<Harness />);
    await userEvent.type(input(), '111026');
    await userEvent.tab();
    expect(input().value).toBe('11.10.2026');
    await userEvent.clear(input());
    await userEvent.type(input(), 'gestern');
    await userEvent.tab();
    expect(input().value).toBe('18.10.2026');
  });

  it('offers today and yesterday as quick picks', async () => {
    renderWithProviders(<Harness quickPicks />);
    await userEvent.click(screen.getByRole('button', { name: 'Gestern' }));
    expect(input().value).toBe('18.10.2026');
    await userEvent.click(screen.getByRole('button', { name: 'Heute' }));
    expect(input().value).toBe('19.10.2026');
  });

  it('has no quick picks unless asked for, and none when compact', () => {
    const { unmount } = renderWithProviders(<Harness />);
    expect(screen.queryByRole('button', { name: 'Heute' })).not.toBeInTheDocument();
    unmount();
    renderWithProviders(<Harness quickPicks compact />);
    expect(screen.queryByRole('button', { name: 'Heute' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Montag|Sonntag/)).not.toBeInTheDocument();
  });

  it('does not take half-typed text as a date', async () => {
    renderWithProviders(<Harness initial="2026-10-11" />);
    await userEvent.clear(input());
    await userEvent.type(input(), '11.10.2');
    await userEvent.tab();
    expect(input().value).toBe('11.10.2026');
  });

  it('takes the short form on Enter as well', async () => {
    renderWithProviders(<Harness />);
    await userEvent.type(input(), '111026{Enter}');
    expect(screen.getByText('Sonntag, 11. Oktober 2026')).toBeInTheDocument();
  });

  it('falls back to the last valid date when the typed one is beyond the maximum', async () => {
    renderWithProviders(<Harness initial="2026-10-11" maxDate="2026-10-19" />);
    await userEvent.clear(input());
    await userEvent.type(input(), '25.12.2026');
    await userEvent.tab();
    expect(input().value).toBe('11.10.2026');
  });

  it('never accepts a year before the allowed range', async () => {
    renderWithProviders(<Harness initial="2026-10-11" />);
    await userEvent.clear(input());
    await userEvent.type(input(), '01.01.1900');
    await userEvent.tab();
    expect(input().value).toBe('11.10.2026');
  });
});

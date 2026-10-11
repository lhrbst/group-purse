import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MonthlyOverview } from './MonthlyOverview';
import { renderWithProviders } from '../test/utils';
import * as api from '../api/generated/report-controller/report-controller';
import type { MonthlyReport } from '../api/model';

vi.mock('../api/generated/report-controller/report-controller', () => ({ useReportMonthlyReport: vi.fn() }));

const full: MonthlyReport = {
  monat: '2026-10',
  gruppe: 'WG Sonne',
  summe: 100,
  belegAnzahl: 2,
  vormonatSumme: 7,
  aenderung: 93,
  aenderungProzent: 1328.6,
  ungeprueftAnzahl: 1,
  ungeprueftSumme: 20,
  zusammenfassung: 'Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.',
  kategorien: [
    { hauptkategorie: 'Haushalt', unterkategorie: undefined, betrag: 60, anteilProzent: 60 },
    { hauptkategorie: 'Lebensmittel', unterkategorie: 'Obst und Gemüse', betrag: 40, anteilProzent: 40 },
  ],
  belege: [
    { datum: '2026-10-01', nummer: 1, konto: 'BARKASSE', haendler: 'REWE', erfasstVon: 'Petra', betrag: 20, zuPruefen: true, positionen: [{ kategorie: 'Obst und Gemüse', betrag: 20 }] },
    { datum: '2026-10-15', nummer: 2, konto: 'VEREINSKONTO', haendler: undefined, erfasstVon: 'Karla', betrag: 80, zuPruefen: false,
      positionen: [{ kategorie: 'Haushalt', betrag: 60 }, { kategorie: 'Obst und Gemüse', betrag: 20 }] },
  ],
};

const empty: MonthlyReport = { ...full, summe: 0, belegAnzahl: 0, aenderung: -7, aenderungProzent: -100, ungeprueftAnzahl: 0, ungeprueftSumme: 0,
  zusammenfassung: 'Keine Ausgaben in diesem Monat.', kategorien: [], belege: [] };

function mockReport(data: MonthlyReport) {
  vi.mocked(api.useReportMonthlyReport).mockReturnValue({ data: { status: 200, data }, isLoading: false, isError: false } as never);
}

describe('MonthlyOverview', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 11));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('shows summary, category table and receipts for the current month', () => {
    mockReport(full);
    renderWithProviders(<MonthlyOverview />);
    expect(vi.mocked(api.useReportMonthlyReport)).toHaveBeenCalledWith({ monat: '2026-10' });
    expect(screen.getByRole('heading', { name: 'Monatsübersicht' })).toBeInTheDocument();
    expect(screen.getByText('Im Oktober 2026 wurden 100,00 € für 2 Belege ausgegeben.')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Hauptkategorie' })).toBeInTheDocument();
    expect(screen.getByText('Lebensmittel')).toBeInTheDocument();
    expect(screen.getByText('REWE')).toBeInTheDocument();
    expect(screen.getByText('zu prüfen')).toBeInTheDocument();
    expect(screen.getByText('Noch nicht geprüft: 1 Beleg', { exact: false })).toBeInTheDocument();
  });

  it('says so for an empty month and shows no tables', () => {
    mockReport(empty);
    renderWithProviders(<MonthlyOverview />);
    expect(screen.getByText('Keine Ausgaben in diesem Monat.')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Hauptkategorie' })).not.toBeInTheDocument();
  });

  it('links the PDF for the selected month and moves between months', async () => {
    mockReport(full);
    renderWithProviders(<MonthlyOverview />);
    const link = screen.getByRole('link', { name: 'PDF herunterladen' });
    expect(link).toHaveAttribute('href', '/api/reports/monthly.pdf?monat=2026-10');
    expect(screen.getByRole('button', { name: 'Nächster Monat' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Vormonat' }));
    expect(vi.mocked(api.useReportMonthlyReport)).toHaveBeenLastCalledWith({ monat: '2026-09' });
    expect(screen.getByRole('link', { name: 'PDF herunterladen' })).toHaveAttribute('href', '/api/reports/monthly.pdf?monat=2026-09');
    expect(screen.getByRole('button', { name: 'Nächster Monat' })).toBeEnabled();
  });
});

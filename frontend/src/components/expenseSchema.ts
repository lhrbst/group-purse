import { z } from 'zod';
import { parseAmount, todayIso } from '../lib/format';

export interface PositionValues {
  /** nur für stabile React-Keys */
  key: string;
  kategorieId: string;
  betrag: string;
}

export interface ExpenseFormValues {
  datum: string;
  positionen: PositionValues[];
  haendler: string;
  zweck: string;
  begruendungOhneBeleg: string;
}

const amount = z
  .string()
  .refine((v) => parseAmount(v) > 0, 'Bitte einen Betrag größer 0 eingeben')
  .refine((v) => Math.round(parseAmount(v) * 100) / 100 === parseAmount(v), 'Höchstens zwei Nachkommastellen');

/** Schema für Datum und Positionen; Foto-oder-Begründung wird separat geprüft, weil Fotos nicht im Formular liegen. */
export const expenseSchema = z.object({
  datum: z
    .string()
    .min(1, 'Bitte ein Datum wählen')
    .refine((v) => v <= todayIso(), 'Das Datum darf nicht in der Zukunft liegen'),
  positionen: z
    .array(z.object({ key: z.string(), kategorieId: z.string().min(1, 'Bitte eine Kategorie wählen'), betrag: amount }))
    .min(1, 'Mindestens eine Position nötig'),
  haendler: z.string(),
  zweck: z.string(),
  begruendungOhneBeleg: z.string(),
});

/** Gesamtbetrag des Belegs: Summe aller lesbaren Positionsbeträge, auf Cent gerundet. */
export function totalOf(positions: { betrag: string }[]): number {
  const sum = positions.reduce((s, p) => {
    const v = parseAmount(p.betrag);
    return Number.isFinite(v) ? s + v : s;
  }, 0);
  return Math.round(sum * 100) / 100;
}

/** Gibt eine Fehlermeldung zurück, wenn weder Foto noch Begründung vorhanden sind. */
export function missingReceiptError(photoCount: number, existingPhotos: number, reason: string): string | null {
  if (photoCount + existingPhotos > 0) return null;
  return reason.trim() === '' ? 'Ohne Belegfoto ist eine Begründung Pflicht' : null;
}

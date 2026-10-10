import { z } from 'zod';
import { parseAmount, todayIso } from '../lib/format';

export interface ExpenseFormValues {
  betrag: string;
  datum: string;
  kategorieId: string;
  haendler: string;
  zweck: string;
  bewohnerKuerzel: string;
  begruendungOhneBeleg: string;
}

/** Schema für Betrag, Datum, Kategorie; Foto-oder-Begründung wird separat geprüft, weil Fotos nicht im Formular liegen. */
export const expenseSchema = z.object({
  betrag: z
    .string()
    .refine((v) => parseAmount(v) > 0, 'Bitte einen Betrag größer 0 eingeben')
    .refine((v) => Math.round(parseAmount(v) * 100) / 100 === parseAmount(v), 'Höchstens zwei Nachkommastellen'),
  datum: z
    .string()
    .min(1, 'Bitte ein Datum wählen')
    .refine((v) => v <= todayIso(), 'Das Datum darf nicht in der Zukunft liegen'),
  kategorieId: z.string().min(1, 'Bitte eine Kategorie wählen'),
  haendler: z.string(),
  zweck: z.string(),
  bewohnerKuerzel: z.string(),
  begruendungOhneBeleg: z.string(),
});

/** Gibt eine Fehlermeldung zurück, wenn weder Foto noch Begründung vorhanden sind. */
export function missingReceiptError(photoCount: number, existingPhotos: number, reason: string): string | null {
  if (photoCount + existingPhotos > 0) return null;
  return reason.trim() === '' ? 'Ohne Belegfoto ist eine Begründung Pflicht' : null;
}

import { useState } from 'react';
import { ActionIcon, Alert, Button, Group, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { useCategoryList } from '../api/generated/category-controller/category-controller';
import type { ExpenseInput } from '../api/model';
import { leafOptions } from '../lib/categories';
import { notifyError } from '../lib/errors';
import { formatEuro, parseAmount, todayIso } from '../lib/format';
import { zodValidate } from '../lib/zodForm';
import { expenseSchema, missingReceiptError, totalOf, type ExpenseFormValues, type PositionValues } from './expenseSchema';
import { PhotoPicker } from './PhotoPicker';
import { ReceiptThumbs } from './ReceiptThumbs';

interface Props {
  initial?: Partial<ExpenseFormValues>;
  /** Neue Fotos anhängen (Kontoausgabe, Beleg ohne Foto). Beim Bearbeiten eines vorhandenen Belegs aus. */
  allowPhotos?: boolean;
  /** Anzahl bereits gespeicherter Fotos und die Buchung, zu der sie gehören */
  existingPhotos?: number;
  existingId?: string;
  submitLabel: string;
  submitting: boolean;
  onSubmit: (input: ExpenseInput, photos: File[]) => Promise<void>;
  hint?: string;
}

export const newPosition = (kategorieId = '', betrag = ''): PositionValues => ({ key: randomId(), kategorieId, betrag });

/** Erfassen und Korrigieren der Belegdaten: Datum, Aufteilung in Kategorien, Händler, Zweck. */
export function ExpenseForm({ initial, allowPhotos = true, existingPhotos = 0, existingId, submitLabel, submitting, onSubmit, hint }: Props) {
  const categories = useCategoryList();
  const [photos, setPhotos] = useState<File[]>([]);

  const form = useForm<ExpenseFormValues>({
    initialValues: {
      datum: todayIso(),
      haendler: '',
      zweck: '',
      begruendungOhneBeleg: '',
      ...initial,
      positionen: initial?.positionen?.length ? initial.positionen : [newPosition()],
    },
    validate: zodValidate(expenseSchema),
  });

  const options = leafOptions(categories.data?.status === 200 ? categories.data.data : []);

  const submit = form.onSubmit(async (v) => {
    const receiptError = missingReceiptError(photos.length, existingPhotos, v.begruendungOhneBeleg);
    if (receiptError) {
      form.setFieldError('begruendungOhneBeleg', receiptError);
      return;
    }
    const input: ExpenseInput = {
      datum: v.datum,
      positionen: v.positionen.map((p) => ({ kategorieId: p.kategorieId, betrag: Math.round(parseAmount(p.betrag) * 100) / 100 })),
      haendler: v.haendler.trim() || undefined,
      zweck: v.zweck.trim() || undefined,
      begruendungOhneBeleg: photos.length + existingPhotos === 0 ? v.begruendungOhneBeleg.trim() : undefined,
    };
    try {
      await onSubmit(input, photos);
    } catch (e) {
      notifyError(e);
    }
  });

  const noPhoto = photos.length + existingPhotos === 0;
  const total = totalOf(form.values.positionen);

  return (
    <form onSubmit={submit}>
      <Stack>
        {hint && (
          <Alert color="blue" variant="light">
            {hint}
          </Alert>
        )}

        {existingPhotos > 0 && existingId && (
          <Stack gap="xs">
            <Text fw={600}>Belegfoto</Text>
            <ReceiptThumbs buchungId={existingId} count={existingPhotos} />
            <Text size="xs" c="dimmed">Zum Vergrößern antippen.</Text>
          </Stack>
        )}
        {allowPhotos && (
          <Stack gap="xs">
            <Text fw={600}>{existingPhotos > 0 ? 'Neue Fotos (ersetzen die vorhandenen)' : 'Belegfoto (optional)'}</Text>
            <PhotoPicker files={photos} onChange={setPhotos} />
          </Stack>
        )}

        <Stack gap="xs">
          <Text fw={600}>Aufteilung nach Kategorien</Text>
          {form.values.positionen.map((pos, i) => (
            <Group key={pos.key} align="flex-start" wrap="nowrap" gap="xs">
              <Select
                style={{ flex: 1, minWidth: 0 }}
                placeholder="Kategorie wählen"
                aria-label={`Kategorie Position ${i + 1}`}
                data={options}
                searchable
                {...form.getInputProps(`positionen.${i}.kategorieId`)}
              />
              <TextInput
                w={104}
                inputMode="decimal"
                placeholder="Betrag"
                aria-label={`Betrag Position ${i + 1}`}
                {...form.getInputProps(`positionen.${i}.betrag`)}
              />
              {form.values.positionen.length > 1 && (
                <ActionIcon variant="subtle" color="red" size="lg" mt={1} aria-label={`Position ${i + 1} entfernen`} onClick={() => form.removeListItem('positionen', i)}>
                  ✕
                </ActionIcon>
              )}
            </Group>
          ))}
          <Group justify="space-between">
            <Button type="button" variant="light" size="compact-md" onClick={() => form.insertListItem('positionen', newPosition())}>
              + Weitere Kategorie
            </Button>
            <Text fw={600}>Summe: {formatEuro(total)}</Text>
          </Group>
          <Text size="xs" c="dimmed">Die Summe ist der Betrag der Ausgabe. Ein Beleg mit nur einer Kategorie braucht nur eine Zeile.</Text>
        </Stack>

        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} required {...form.getInputProps('datum')} />
        <TextInput label="Händler" {...form.getInputProps('haendler')} />
        <TextInput label="Zweck" placeholder="z. B. Einkauf Wochenende" {...form.getInputProps('zweck')} />

        {noPhoto && (
          <Textarea
            label="Begründung ohne Beleg"
            description="Ohne Foto ist eine Begründung Pflicht. Die Ausgabe wird in der Prüfliste markiert."
            minRows={2}
            {...form.getInputProps('begruendungOhneBeleg')}
          />
        )}

        <Button type="submit" size="lg" loading={submitting}>
          {submitLabel}
        </Button>
      </Stack>
    </form>
  );
}

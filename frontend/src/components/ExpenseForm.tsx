import { useRef, useState } from 'react';
import { ActionIcon, Alert, Button, Group, Image, Loader, Select, SimpleGrid, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { randomId } from '@mantine/hooks';
import { useCategoryList } from '../api/generated/category-controller/category-controller';
import { useReceiptRecognize } from '../api/generated/receipt-controller/receipt-controller';
import type { ExpenseInput, ReceiptSuggestion } from '../api/model';
import { leafOptions } from '../lib/categories';
import { resizeImage } from '../lib/image';
import { notifyError } from '../lib/errors';
import { formatEuro, parseAmount, todayIso } from '../lib/format';
import { zodValidate } from '../lib/zodForm';
import { expenseSchema, missingReceiptError, totalOf, type ExpenseFormValues, type PositionValues } from './expenseSchema';

interface PhotoItem {
  file: File;
  url: string;
}

interface Props {
  /** create: neue Ausgabe mit Belegerkennung; edit: abgelehnte Ausgabe korrigieren */
  mode: 'create' | 'edit';
  initial?: Partial<ExpenseFormValues>;
  /** Anzahl bereits gespeicherter Fotos (nur edit) */
  existingPhotos?: number;
  existingId?: string;
  submitLabel: string;
  submitting: boolean;
  onSubmit: (input: ExpenseInput, photos: File[]) => Promise<void>;
  hint?: string;
}

export const newPosition = (kategorieId = '', betrag = ''): PositionValues => ({ key: randomId(), kategorieId, betrag });

const formatAmountInput = (n: number) => n.toFixed(2).replace('.', ',');

export function ExpenseForm({ mode, initial, existingPhotos = 0, existingId, submitLabel, submitting, onSubmit, hint }: Props) {
  const categories = useCategoryList();
  const recognize = useReceiptRecognize();
  const [photos, setPhotos] = useState<PhotoItem[]>([]);
  const [reading, setReading] = useState(false);
  const [suggested, setSuggested] = useState(false);
  const [photoError, setPhotoError] = useState<string | null>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const galleryRef = useRef<HTMLInputElement>(null);

  const form = useForm<ExpenseFormValues>({
    initialValues: {
      datum: todayIso(),
      haendler: '',
      zweck: '',
      bewohnerKuerzel: '',
      begruendungOhneBeleg: '',
      ...initial,
      positionen: initial?.positionen?.length ? initial.positionen : [newPosition()],
    },
    validate: zodValidate(expenseSchema),
  });

  const allCategories = categories.data?.status === 200 ? categories.data.data : [];
  const options = leafOptions(allCategories);

  function applySuggestion(s: ReceiptSuggestion) {
    const valid = new Set(options.map((o) => o.value));
    const rows = (s.positionen ?? []).map((p) =>
      newPosition(p.kategorieId && valid.has(p.kategorieId) ? p.kategorieId : '', p.betrag != null ? formatAmountInput(p.betrag) : ''),
    );
    form.setValues({
      ...(s.datum ? { datum: s.datum } : {}),
      ...(s.haendler ? { haendler: s.haendler } : {}),
      ...(rows.length > 0 ? { positionen: rows } : {}),
    });
    setSuggested(true);
  }

  async function addFiles(list: FileList | null) {
    if (!list || list.length === 0) return;
    setPhotoError(null);
    const added: PhotoItem[] = [];
    try {
      for (const original of Array.from(list)) {
        const blob = await resizeImage(original, 1500);
        const ext = blob.type === 'image/webp' ? 'webp' : 'jpg';
        const file = new File([blob], `beleg-${Date.now()}-${added.length}.${ext}`, { type: blob.type });
        added.push({ file, url: URL.createObjectURL(file) });
      }
    } catch (e) {
      setPhotoError(e instanceof Error ? e.message : 'Das Foto konnte nicht verarbeitet werden.');
      return;
    }
    const firstPhoto = photos.length === 0 && mode === 'create';
    setPhotos((p) => [...p, ...added]);
    if (firstPhoto && added.length > 0) {
      setReading(true);
      try {
        const res = await recognize.mutateAsync({ data: { photo: added[0].file } });
        if (res.status === 200) applySuggestion(res.data);
      } catch {
        // Erkennung ist optional: bei Fehlern füllt die Person die Felder selbst aus
      } finally {
        setReading(false);
      }
    }
  }

  function removePhoto(index: number) {
    setPhotos((p) => {
      URL.revokeObjectURL(p[index].url);
      return p.filter((_, i) => i !== index);
    });
  }

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
      bewohnerKuerzel: v.bewohnerKuerzel.trim() || undefined,
      begruendungOhneBeleg: photos.length + existingPhotos === 0 ? v.begruendungOhneBeleg.trim() : undefined,
    };
    try {
      await onSubmit(input, photos.map((p) => p.file));
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

        <Stack gap="xs">
          <Text fw={600}>Belegfoto</Text>
          {existingPhotos > 0 && existingId && (
            <Group gap="xs">
              {Array.from({ length: existingPhotos }, (_, i) => (
                <Image key={i} src={`/api/receipts/${existingId}/${i}`} alt={`Gespeicherter Beleg ${i + 1}`} w={72} h={72} fit="cover" radius="sm" />
              ))}
              <Text size="xs" c="dimmed">
                Gespeicherte Fotos bleiben erhalten, wenn du kein neues hinzufügst.
              </Text>
            </Group>
          )}
          {photos.length > 0 && (
            <SimpleGrid cols={{ base: 2, sm: 4 }}>
              {photos.map((p, i) => (
                <Stack key={p.url} gap={4} align="center">
                  <Image src={p.url} alt={`Neues Foto ${i + 1}`} h={110} fit="cover" radius="sm" />
                  <Button size="compact-xs" variant="subtle" color="red" type="button" onClick={() => removePhoto(i)}>
                    Entfernen
                  </Button>
                </Stack>
              ))}
            </SimpleGrid>
          )}
          <Group>
            <Button type="button" size="md" onClick={() => cameraRef.current?.click()}>
              {photos.length === 0 ? 'Foto aufnehmen' : 'Weiteres Foto aufnehmen'}
            </Button>
            <Button type="button" size="md" variant="default" onClick={() => galleryRef.current?.click()}>
              Aus Galerie wählen
            </Button>
          </Group>
          <input ref={cameraRef} type="file" accept="image/*" capture="environment" hidden onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }} />
          <input ref={galleryRef} type="file" accept="image/*" multiple hidden onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }} />
          {photoError && <Text c="red" size="sm">{photoError}</Text>}
          {reading && (
            <Group gap="xs">
              <Loader size="xs" />
              <Text size="sm" c="dimmed">Beleg wird gelesen und aufgeteilt …</Text>
            </Group>
          )}
          {suggested && !reading && (
            <Alert color="green" variant="light">
              Vorschlag aus dem Beleg übernommen. Bitte die Aufteilung und das Datum prüfen.
            </Alert>
          )}
        </Stack>

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
          <Text size="xs" c="dimmed">
            Die Summe wird von der Barkasse abgezogen. Ein Beleg mit nur einer Kategorie braucht nur eine Zeile.
          </Text>
        </Stack>

        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} required {...form.getInputProps('datum')} />
        <TextInput label="Händler" {...form.getInputProps('haendler')} />
        <TextInput label="Zweck" placeholder="z. B. Einkauf Wochenende" {...form.getInputProps('zweck')} />
        <TextInput label="Bewohner (Kürzel, optional)" description="Nur wenn die Ausgabe einer Person zugeordnet werden soll" {...form.getInputProps('bewohnerKuerzel')} />

        {noPhoto && (
          <Textarea
            label="Begründung ohne Beleg"
            description="Ohne Foto ist eine Begründung Pflicht. Die Ausgabe wird in der Prüfliste markiert."
            minRows={2}
            {...form.getInputProps('begruendungOhneBeleg')}
          />
        )}

        <Button type="submit" size="lg" loading={submitting} disabled={reading}>
          {submitLabel}
        </Button>
      </Stack>
    </form>
  );
}

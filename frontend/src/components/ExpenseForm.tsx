import { useRef, useState } from 'react';
import { Alert, Button, Group, Image, Loader, Select, SimpleGrid, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { useCategoryList } from '../api/generated/category-controller/category-controller';
import { useReceiptRecognize } from '../api/generated/receipt-controller/receipt-controller';
import type { ExpenseInput, ReceiptSuggestion } from '../api/model';
import { resizeImage } from '../lib/image';
import { notifyError } from '../lib/errors';
import { formatEuro, parseAmount, todayIso } from '../lib/format';
import { zodValidate } from '../lib/zodForm';
import { expenseSchema, missingReceiptError, type ExpenseFormValues } from './expenseSchema';

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
  /** Kassenverwalter dürfen auch Kontoausgaben erfassen; dort gibt es keine Belegerkennung für Barkasse-Hinweise */
  hint?: string;
}

const EMPTY: ExpenseFormValues = {
  betrag: '',
  datum: todayIso(),
  kategorieId: '',
  haendler: '',
  zweck: '',
  bewohnerKuerzel: '',
  begruendungOhneBeleg: '',
};

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
    initialValues: { ...EMPTY, ...initial },
    validate: zodValidate(expenseSchema),
  });

  const options =
    categories.data?.status === 200 ? categories.data.data.map((c) => ({ value: c.id!, label: c.name ?? '' })) : [];

  function applySuggestion(s: ReceiptSuggestion) {
    form.setValues({
      ...(s.betrag != null ? { betrag: String(s.betrag).replace('.', ',') } : {}),
      ...(s.datum ? { datum: s.datum } : {}),
      ...(s.kategorieId && options.some((o) => o.value === s.kategorieId) ? { kategorieId: s.kategorieId } : {}),
      ...(s.haendler ? { haendler: s.haendler } : {}),
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
      betrag: Math.round(parseAmount(v.betrag) * 100) / 100,
      datum: v.datum,
      kategorieId: v.kategorieId,
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
  const amount = parseAmount(form.values.betrag);

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
              <Text size="sm" c="dimmed">Beleg wird gelesen …</Text>
            </Group>
          )}
          {suggested && !reading && (
            <Alert color="green" variant="light">
              Vorschlag aus dem Beleg übernommen. Bitte Betrag, Datum und Kategorie prüfen.
            </Alert>
          )}
        </Stack>

        <TextInput label="Betrag (€)" inputMode="decimal" placeholder="z. B. 12,50" required {...form.getInputProps('betrag')} />
        {Number.isFinite(amount) && amount > 0 && <Text size="xs" c="dimmed" mt={-8}>{formatEuro(amount)} werden von der Barkasse abgezogen.</Text>}
        <DateInput label="Datum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} required {...form.getInputProps('datum')} />
        <Select label="Kategorie" placeholder="Bitte wählen" data={options} searchable required {...form.getInputProps('kategorieId')} />
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

import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Group, Image, SimpleGrid, Stack, Text } from '@mantine/core';
import { resizeImage } from '../lib/image';

interface Props {
  files: File[];
  onChange: (files: File[]) => void;
  disabled?: boolean;
}

/** Kamera oder Galerie; Fotos werden im Browser auf etwa 1500 Pixel Breite verkleinert. */
export function PhotoPicker({ files, onChange, disabled }: Props) {
  const camera = useRef<HTMLInputElement>(null);
  const gallery = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const urls = useMemo(() => files.map((f) => URL.createObjectURL(f)), [files]);

  useEffect(() => () => urls.forEach((u) => URL.revokeObjectURL(u)), [urls]);

  async function add(list: FileList | null) {
    if (!list || list.length === 0) return;
    setError(null);
    setBusy(true);
    try {
      const added: File[] = [];
      for (const original of Array.from(list)) {
        const blob = await resizeImage(original, 1500);
        const ext = blob.type === 'image/webp' ? 'webp' : 'jpg';
        added.push(new File([blob], `beleg-${Date.now()}-${added.length}.${ext}`, { type: blob.type }));
      }
      onChange([...files, ...added]);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Das Foto konnte nicht verarbeitet werden.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Stack gap="xs">
      {files.length > 0 && (
        <SimpleGrid cols={{ base: 2, sm: 4 }}>
          {files.map((_, i) => (
            <Stack key={urls[i]} gap={4} align="center">
              <Image src={urls[i]} alt={`Foto ${i + 1}`} h={120} fit="cover" radius="sm" />
              <Button size="compact-xs" variant="subtle" color="red" type="button" disabled={disabled} onClick={() => onChange(files.filter((_, j) => j !== i))}>
                Entfernen
              </Button>
            </Stack>
          ))}
        </SimpleGrid>
      )}
      <Group>
        <Button type="button" size="lg" loading={busy} disabled={disabled} onClick={() => camera.current?.click()}>
          {files.length === 0 ? 'Foto aufnehmen' : 'Weiteres Foto aufnehmen'}
        </Button>
        <Button type="button" size="lg" variant="default" disabled={disabled || busy} onClick={() => gallery.current?.click()}>
          Aus Galerie wählen
        </Button>
      </Group>
      <input ref={camera} type="file" accept="image/*" capture="environment" hidden onChange={(e) => { add(e.target.files); e.target.value = ''; }} />
      <input ref={gallery} type="file" accept="image/*" multiple hidden onChange={(e) => { add(e.target.files); e.target.value = ''; }} />
      {error && <Text c="red" size="sm">{error}</Text>}
    </Stack>
  );
}

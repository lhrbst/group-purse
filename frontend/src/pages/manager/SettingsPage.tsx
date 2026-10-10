import { useState } from 'react';
import { Button, Card, Group, Select, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { useCategoryCreate, useCategoryHide, useCategoryList } from '../../api/generated/category-controller/category-controller';
import { useMemberGroup, useMemberSettings } from '../../api/generated/member-controller/member-controller';
import { LoadError, Loading } from '../../components/Loading';
import { notifyError, notifySuccess } from '../../lib/errors';
import { parseAmount } from '../../lib/format';
import { zodValidate } from '../../lib/zodForm';

function MinimumForm({ initial }: { initial: number }) {
  const settings = useMemberSettings();
  const qc = useQueryClient();
  const form = useForm({
    initialValues: { mindestbetrag: String(initial).replace('.', ',') },
    validate: zodValidate(z.object({ mindestbetrag: z.string().refine((v) => parseAmount(v) >= 0, 'Bitte einen Betrag ab 0 eingeben') })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await settings.mutateAsync({ data: { mindestbetragKasse: parseAmount(v.mindestbetrag) } });
          await qc.invalidateQueries();
          notifySuccess('Gespeichert.');
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Group align="flex-end">
        <TextInput label="Mindestbetrag der Barkasse (€)" description="Darunter erscheint ein Hinweis in der App. Vorschlag: 50 €" inputMode="decimal" {...form.getInputProps('mindestbetrag')} />
        <Button type="submit" loading={settings.isPending}>Speichern</Button>
      </Group>
    </form>
  );
}

function Categories() {
  const q = useCategoryList({ alle: true });
  const create = useCategoryCreate();
  const hide = useCategoryHide();
  const qc = useQueryClient();
  const [parent, setParent] = useState<string | null>(null);
  const form = useForm({ initialValues: { name: '' }, validate: zodValidate(z.object({ name: z.string().trim().min(1, 'Bitte einen Namen eingeben') })) });

  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const all = q.data.data;
  const mains = all.filter((c) => !c.parentId);

  async function toggle(id: string, visible: boolean) {
    try {
      await hide.mutateAsync({ id, data: { hidden: !visible } });
      await qc.invalidateQueries();
    } catch (err) {
      notifyError(err);
    }
  }

  return (
    <Stack gap="xs">
      {mains.map((main) => (
        <Stack key={main.id} gap={4}>
          <Group justify="space-between">
            <Text fw={600} c={main.ausgeblendet ? 'dimmed' : undefined}>
              {main.name}
              {!main.global && ' (eigene)'}
            </Text>
            <Switch aria-label={`${main.name} verwenden`} checked={!main.ausgeblendet} onChange={(e) => toggle(main.id!, e.currentTarget.checked)} />
          </Group>
          {all
            .filter((c) => c.parentId === main.id)
            .map((sub) => (
              <Group key={sub.id} justify="space-between" pl="lg">
                <Text size="sm" c={sub.ausgeblendet || main.ausgeblendet ? 'dimmed' : undefined}>
                  {sub.name}
                  {!sub.global && ' (eigene)'}
                </Text>
                <Switch size="sm" aria-label={`${sub.name} verwenden`} checked={!sub.ausgeblendet} onChange={(e) => toggle(sub.id!, e.currentTarget.checked)} />
              </Group>
            ))}
        </Stack>
      ))}
      <form
        onSubmit={form.onSubmit(async (v) => {
          try {
            await create.mutateAsync({ data: { name: v.name.trim(), parentId: parent ?? undefined } });
            await qc.invalidateQueries();
            form.reset();
            setParent(null);
            notifySuccess('Kategorie angelegt.');
          } catch (e) {
            notifyError(e);
          }
        })}
      >
        <Group align="flex-end" mt="sm">
          <TextInput label="Eigene Kategorie hinzufügen" {...form.getInputProps('name')} />
          <Select
            label="Unterkategorie von"
            placeholder="keine (Hauptkategorie)"
            clearable
            data={mains.map((m) => ({ value: m.id!, label: m.name ?? '' }))}
            value={parent}
            onChange={setParent}
          />
          <Button type="submit" variant="light" loading={create.isPending}>Hinzufügen</Button>
        </Group>
      </form>
    </Stack>
  );
}

export function SettingsPage() {
  const group = useMemberGroup();
  if (group.isLoading) return <Loading />;
  if (group.isError || group.data?.status !== 200) return <LoadError error={group.error} />;
  const g = group.data.data;
  return (
    <Stack maw={640}>
      <Title order={2}>Einstellungen</Title>
      <Text c="dimmed">Wohngruppe: {g.name}</Text>
      <Card withBorder>
        <MinimumForm key={g.mindestbetragKasse} initial={g.mindestbetragKasse ?? 50} />
      </Card>
      <Card withBorder>
        <Title order={4} mb="xs">Kategorien</Title>
        <Text size="sm" c="dimmed" mb="sm">
          Auf einem Beleg wird jede Zeile einer Kategorie zugeordnet; bei Kategorien mit Unterkategorien (z. B. Lebensmittel) einer Unterkategorie.
          Ausgeblendete Kategorien stehen bei neuen Ausgaben nicht mehr zur Auswahl; alte Buchungen behalten sie.
        </Text>
        <Categories />
      </Card>
    </Stack>
  );
}

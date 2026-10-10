import { Button, Card, Group, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
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
        <TextInput label="Mindestbetrag der Barkasse (€)" description="Darunter werden alle Kassenverwalter benachrichtigt. Vorschlag: 50 €" inputMode="decimal" {...form.getInputProps('mindestbetrag')} />
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
  const form = useForm({ initialValues: { name: '' }, validate: zodValidate(z.object({ name: z.string().trim().min(1, 'Bitte einen Namen eingeben') })) });

  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  return (
    <Stack gap="xs">
      {q.data.data.map((c) => (
        <Group key={c.id} justify="space-between">
          <Text c={c.ausgeblendet ? 'dimmed' : undefined}>{c.name}{!c.global && ' (eigene)'}</Text>
          <Switch
            aria-label={`${c.name} verwenden`}
            checked={!c.ausgeblendet}
            onChange={async (e) => {
              try {
                await hide.mutateAsync({ id: c.id!, data: { hidden: !e.currentTarget.checked } });
                await qc.invalidateQueries();
              } catch (err) {
                notifyError(err);
              }
            }}
          />
        </Group>
      ))}
      <form
        onSubmit={form.onSubmit(async (v) => {
          try {
            await create.mutateAsync({ data: { name: v.name.trim() } });
            await qc.invalidateQueries();
            form.reset();
            notifySuccess('Kategorie angelegt.');
          } catch (e) {
            notifyError(e);
          }
        })}
      >
        <Group align="flex-end" mt="sm">
          <TextInput label="Eigene Kategorie hinzufügen" {...form.getInputProps('name')} />
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
        <Text size="sm" c="dimmed" mb="sm">Ausgeblendete Kategorien stehen bei neuen Ausgaben nicht mehr zur Auswahl; alte Buchungen behalten sie.</Text>
        <Categories />
      </Card>
    </Stack>
  );
}

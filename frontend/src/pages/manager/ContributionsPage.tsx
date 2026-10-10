import { useState } from 'react';
import { ActionIcon, Badge, Button, Checkbox, Group, MultiSelect, Select, Stack, Switch, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import {
  useContributionBook,
  useContributionCancel,
  useContributionCreateFamily,
  useContributionFamilies,
  useContributionGrid,
  useContributionList,
  useContributionSetSollbetrag,
  useContributionUpdateFamily,
} from '../../api/generated/contribution-controller/contribution-controller';
import type { Cell, FamilyDto } from '../../api/model';
import { ActionModal } from '../../components/ActionModal';
import { Amount } from '../../components/Amount';
import { LoadError, Loading } from '../../components/Loading';
import { ReasonModal } from '../../components/ReasonModal';
import { notifyError, notifySuccess } from '../../lib/errors';
import { currentMonth, formatDate, formatEuro, formatMonth, parseAmount, shiftMonth, todayIso } from '../../lib/format';
import { zodValidate } from '../../lib/zodForm';

const CELL_STYLE: Record<string, { color: string; label: string }> = {
  BEZAHLT: { color: 'green', label: 'bezahlt' },
  TEILWEISE: { color: 'yellow', label: 'teilweise' },
  OFFEN: { color: 'gray', label: 'offen' },
  UEBERFAELLIG: { color: 'red', label: 'überfällig' },
  KEIN_SOLL: { color: 'gray', label: '–' },
};

function CellBadge({ cell }: { cell: Cell }) {
  const style = CELL_STYLE[cell.status ?? 'KEIN_SOLL'];
  return (
    <Badge
      color={style.color}
      variant={cell.status === 'KEIN_SOLL' ? 'transparent' : 'light'}
      title={`${formatMonth(cell.monat ?? '')}: bezahlt ${formatEuro(cell.bezahlt)} von ${formatEuro(cell.soll)}`}
    >
      {style.label}
    </Badge>
  );
}

function monthOptions() {
  const out: { value: string; label: string }[] = [];
  for (let i = -18; i <= 3; i++) {
    const m = shiftMonth(currentMonth(), i);
    out.push({ value: m, label: formatMonth(m) });
  }
  return out;
}

function PaymentForm({ families, onClose }: { families: FamilyDto[]; onClose: () => void }) {
  const book = useContributionBook();
  const qc = useQueryClient();
  const form = useForm({
    initialValues: { familieId: '', betrag: '', eingangsdatum: todayIso(), monate: [currentMonth()], notiz: '' },
    validate: zodValidate(
      z.object({
        familieId: z.string().min(1, 'Bitte eine Familie wählen'),
        betrag: z.string().refine((v) => parseAmount(v) > 0, 'Bitte einen Betrag größer 0 eingeben'),
        eingangsdatum: z.string().min(1, 'Bitte Datum wählen'),
        monate: z.array(z.string()).min(1, 'Mindestens einen Monat wählen'),
        notiz: z.string(),
      }),
    ),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await book.mutateAsync({
            data: { familieId: v.familieId, betrag: parseAmount(v.betrag), eingangsdatum: v.eingangsdatum, monate: v.monate, notiz: v.notiz.trim() || undefined },
          });
          await qc.invalidateQueries();
          notifySuccess('Zahlungseingang gebucht.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Select
          label="Familie"
          searchable
          data={families.filter((f) => f.aktiv).map((f) => ({ value: f.id!, label: f.name ?? '' }))}
          {...form.getInputProps('familieId')}
          onChange={(value) => {
            form.setFieldValue('familieId', value ?? '');
            const fam = families.find((f) => f.id === value);
            if (fam?.aktuellerSollbetrag && form.values.betrag === '') {
              form.setFieldValue('betrag', String(fam.aktuellerSollbetrag).replace('.', ','));
            }
          }}
        />
        <TextInput label="Betrag (€)" inputMode="decimal" {...form.getInputProps('betrag')} />
        <DateInput label="Eingangsdatum" valueFormat="DD.MM.YYYY" maxDate={todayIso()} {...form.getInputProps('eingangsdatum')} />
        <MultiSelect label="Monat(e), für die gezahlt wurde" data={monthOptions()} searchable {...form.getInputProps('monate')} />
        <Text size="xs" c="dimmed" mt={-8}>
          Die App verteilt Zahlungen automatisch auf die ältesten offenen Monate. Mehr als fällig ist, bleibt als Guthaben stehen.
        </Text>
        <TextInput label="Notiz (optional)" {...form.getInputProps('notiz')} />
        <Button type="submit" loading={book.isPending}>
          Zahlung buchen
        </Button>
      </Stack>
    </form>
  );
}

function FamilyForm({ family, onClose }: { family?: FamilyDto; onClose: () => void }) {
  const create = useContributionCreateFamily();
  const update = useContributionUpdateFamily();
  const qc = useQueryClient();
  const form = useForm({
    initialValues: {
      name: family?.name ?? '',
      bewohnerKuerzel: family?.bewohnerKuerzel ?? '',
      kontaktEmail: family?.kontaktEmail ?? '',
      faelligkeitstag: String(family?.faelligkeitstag ?? 1),
      sollbetrag: '',
      gueltigAb: `${currentMonth()}-01`,
      aktiv: family?.aktiv ?? true,
    },
    validate: zodValidate(
      z.object({
        name: z.string().trim().min(1, 'Bitte Namen eingeben'),
        bewohnerKuerzel: z.string(),
        kontaktEmail: z.string().refine((v) => v === '' || /^\S+@\S+\.\S+$/.test(v), 'Keine gültige E-Mail-Adresse'),
        faelligkeitstag: z.string().refine((v) => Number(v) >= 1 && Number(v) <= 28, 'Tag zwischen 1 und 28'),
        sollbetrag: family ? z.string() : z.string().refine((v) => parseAmount(v) >= 0, 'Bitte den Monatsbeitrag eingeben'),
        gueltigAb: z.string(),
        aktiv: z.boolean(),
      }),
    ),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        const base = {
          name: v.name.trim(),
          bewohnerKuerzel: v.bewohnerKuerzel.trim() || undefined,
          kontaktEmail: v.kontaktEmail.trim() || undefined,
          faelligkeitstag: Number(v.faelligkeitstag),
          aktiv: v.aktiv,
        };
        try {
          if (family) {
            await update.mutateAsync({ id: family.id!, data: base });
          } else {
            await create.mutateAsync({ data: { ...base, sollbetrag: parseAmount(v.sollbetrag), gueltigAb: v.gueltigAb } });
          }
          await qc.invalidateQueries();
          notifySuccess(family ? 'Familie gespeichert.' : 'Familie angelegt.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <TextInput label="Familienname" data-autofocus {...form.getInputProps('name')} />
        <TextInput label="Bewohner (Vorname oder Kürzel)" {...form.getInputProps('bewohnerKuerzel')} />
        <TextInput label="Kontakt-E-Mail" description="Für Erinnerungen bei überfälligen Beiträgen" {...form.getInputProps('kontaktEmail')} />
        <TextInput label="Fälligkeitstag im Monat" inputMode="numeric" {...form.getInputProps('faelligkeitstag')} />
        {!family && (
          <>
            <TextInput label="Monatlicher Sollbetrag (€)" inputMode="decimal" {...form.getInputProps('sollbetrag')} />
            <DateInput label="Gilt ab" valueFormat="DD.MM.YYYY" {...form.getInputProps('gueltigAb')} />
          </>
        )}
        {family && <Switch label="Aktiv" {...form.getInputProps('aktiv', { type: 'checkbox' })} />}
        <Button type="submit" loading={create.isPending || update.isPending}>
          Speichern
        </Button>
      </Stack>
    </form>
  );
}

function SollbetragForm({ family, onClose }: { family: FamilyDto; onClose: () => void }) {
  const set = useContributionSetSollbetrag();
  const qc = useQueryClient();
  const form = useForm({
    initialValues: { betrag: String(family.aktuellerSollbetrag ?? '').replace('.', ','), gueltigAb: `${currentMonth()}-01` },
    validate: zodValidate(z.object({ betrag: z.string().refine((v) => parseAmount(v) >= 0, 'Bitte einen Betrag eingeben'), gueltigAb: z.string().min(1, 'Bitte Datum wählen') })),
  });
  return (
    <form
      onSubmit={form.onSubmit(async (v) => {
        try {
          await set.mutateAsync({ id: family.id!, data: { betrag: parseAmount(v.betrag), gueltigAb: v.gueltigAb } });
          await qc.invalidateQueries();
          notifySuccess('Sollbetrag gespeichert. Frühere Monate bleiben unverändert.');
          onClose();
        } catch (e) {
          notifyError(e);
        }
      })}
    >
      <Stack>
        <Text size="sm" c="dimmed">Der neue Betrag gilt ab dem gewählten Monat. Frühere Monate behalten ihren alten Sollbetrag.</Text>
        <TextInput label="Neuer Monatsbeitrag (€)" inputMode="decimal" data-autofocus {...form.getInputProps('betrag')} />
        <DateInput label="Gilt ab" valueFormat="DD.MM.YYYY" {...form.getInputProps('gueltigAb')} />
        <Button type="submit" loading={set.isPending}>Speichern</Button>
      </Stack>
    </form>
  );
}

function GridTab({ onPay }: { onPay: () => void }) {
  const [end, setEnd] = useState(currentMonth());
  const grid = useContributionGrid({ von: shiftMonth(end, -11), bis: end });

  return (
    <Stack>
      <Group justify="space-between">
        <Group gap="xs">
          <ActionIcon variant="default" aria-label="Früher" onClick={() => setEnd(shiftMonth(end, -6))}>‹</ActionIcon>
          <Text size="sm">{formatMonth(shiftMonth(end, -11))} – {formatMonth(end)}</Text>
          <ActionIcon variant="default" aria-label="Später" onClick={() => setEnd(shiftMonth(end, 6))}>›</ActionIcon>
          <Button variant="subtle" size="compact-sm" onClick={() => setEnd(currentMonth())}>Heute</Button>
        </Group>
        <Group>
          <Button onClick={onPay}>Zahlung buchen</Button>
        </Group>
      </Group>
      {grid.isLoading ? (
        <Loading />
      ) : grid.isError || grid.data?.status !== 200 ? (
        <LoadError error={grid.error} />
      ) : grid.data.data.zeilen?.length === 0 ? (
        <Text c="dimmed">Noch keine Familien angelegt. Lege sie im Reiter „Familien“ an.</Text>
      ) : (
        <Table.ScrollContainer minWidth={900}>
          <Table withTableBorder withColumnBorders>
            <Table.Thead>
              <Table.Tr>
                <Table.Th style={{ position: 'sticky', left: 0, background: 'var(--mantine-color-body)' }}>Familie</Table.Th>
                {grid.data.data.monate?.map((m) => (
                  <Table.Th key={m} ta="center">{formatMonth(m)}</Table.Th>
                ))}
                <Table.Th ta="right">Guthaben</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {grid.data.data.zeilen?.map((row) => (
                <Table.Tr key={row.familieId}>
                  <Table.Td style={{ position: 'sticky', left: 0, background: 'var(--mantine-color-body)' }}>
                    <Text fw={600} size="sm">{row.name}</Text>
                    {row.bewohnerKuerzel && <Text size="xs" c="dimmed">{row.bewohnerKuerzel}</Text>}
                  </Table.Td>
                  {row.cells?.map((c) => (
                    <Table.Td key={c.monat} ta="center"><CellBadge cell={c} /></Table.Td>
                  ))}
                  <Table.Td ta="right">{(row.guthaben ?? 0) > 0 ? <Amount value={row.guthaben} /> : ''}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

function FamiliesTab() {
  const q = useContributionFamilies();
  const [edit, setEdit] = useState<FamilyDto | 'new' | null>(null);
  const [soll, setSoll] = useState<FamilyDto | null>(null);
  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  return (
    <Stack>
      <Group justify="flex-end">
        <Button onClick={() => setEdit('new')}>Familie anlegen</Button>
      </Group>
      <Table.ScrollContainer minWidth={640}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Familie</Table.Th>
              <Table.Th>Bewohner</Table.Th>
              <Table.Th>E-Mail</Table.Th>
              <Table.Th>Fällig am</Table.Th>
              <Table.Th ta="right">Monatsbeitrag</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {q.data.data.map((f) => (
              <Table.Tr key={f.id} c={f.aktiv ? undefined : 'dimmed'}>
                <Table.Td>{f.name}{!f.aktiv && ' (inaktiv)'}</Table.Td>
                <Table.Td>{f.bewohnerKuerzel}</Table.Td>
                <Table.Td>{f.kontaktEmail}</Table.Td>
                <Table.Td>{f.faelligkeitstag}.</Table.Td>
                <Table.Td ta="right"><Amount value={f.aktuellerSollbetrag} /></Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end" wrap="nowrap">
                    <Button size="compact-xs" variant="light" onClick={() => setSoll(f)}>Sollbetrag ändern</Button>
                    <Button size="compact-xs" variant="subtle" onClick={() => setEdit(f)}>Bearbeiten</Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <ActionModal opened={edit !== null} onClose={() => setEdit(null)} title={edit === 'new' ? 'Familie anlegen' : 'Familie bearbeiten'}>
        {edit !== null && <FamilyForm family={edit === 'new' ? undefined : edit} onClose={() => setEdit(null)} />}
      </ActionModal>
      <ActionModal opened={soll !== null} onClose={() => setSoll(null)} title={`Sollbetrag ${soll?.name ?? ''}`}>
        {soll && <SollbetragForm family={soll} onClose={() => setSoll(null)} />}
      </ActionModal>
    </Stack>
  );
}

function PaymentsTab() {
  const q = useContributionList();
  const cancel = useContributionCancel();
  const qc = useQueryClient();
  const [item, setItem] = useState<{ id: string; name: string } | null>(null);
  const [showCancelled, setShowCancelled] = useState(false);
  if (q.isLoading) return <Loading />;
  if (q.isError || q.data?.status !== 200) return <LoadError error={q.error} />;
  const rows = q.data.data.filter((p) => showCancelled || !p.storniert);
  return (
    <Stack>
      <Checkbox label="Stornierte anzeigen" checked={showCancelled} onChange={(e) => setShowCancelled(e.currentTarget.checked)} />
      <Table.ScrollContainer minWidth={640}>
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Eingang</Table.Th>
              <Table.Th>Familie</Table.Th>
              <Table.Th ta="right">Betrag</Table.Th>
              <Table.Th>Monate</Table.Th>
              <Table.Th>Notiz</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((p) => (
              <Table.Tr key={p.id} c={p.storniert ? 'dimmed' : undefined}>
                <Table.Td>{formatDate(p.eingangsdatum)}</Table.Td>
                <Table.Td>{p.familieName}</Table.Td>
                <Table.Td ta="right"><Amount value={p.betrag} td={p.storniert ? 'line-through' : undefined} /></Table.Td>
                <Table.Td>{p.monate?.map(formatMonth).join(', ')}</Table.Td>
                <Table.Td>{p.storniert ? `storniert: ${p.stornoBegruendung}` : p.notiz}</Table.Td>
                <Table.Td>
                  {!p.storniert && (
                    <Button size="compact-xs" variant="subtle" color="gray" onClick={() => setItem({ id: p.id!, name: p.familieName ?? '' })}>
                      Stornieren
                    </Button>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
            {rows.length === 0 && (
              <Table.Tr><Table.Td colSpan={6}><Text c="dimmed" ta="center">Noch keine Zahlungen gebucht.</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <ReasonModal
        opened={item !== null}
        onClose={() => setItem(null)}
        title={`Zahlung von ${item?.name} stornieren`}
        label="Begründung"
        confirmLabel="Stornieren"
        color="red"
        onConfirm={async (reason) => {
          try {
            await cancel.mutateAsync({ id: item!.id, data: { begruendung: reason } });
            await qc.invalidateQueries();
            notifySuccess('Zahlung storniert.');
          } catch (e) {
            notifyError(e);
            throw e;
          }
        }}
      />
    </Stack>
  );
}

export function ContributionsPage() {
  const families = useContributionFamilies();
  const [pay, setPay] = useState(false);
  return (
    <Stack>
      <Title order={2}>Elternbeiträge</Title>
      <Tabs defaultValue="grid" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="grid">Raster</Tabs.Tab>
          <Tabs.Tab value="families">Familien</Tabs.Tab>
          <Tabs.Tab value="payments">Zahlungen</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="grid" pt="md"><GridTab onPay={() => setPay(true)} /></Tabs.Panel>
        <Tabs.Panel value="families" pt="md"><FamiliesTab /></Tabs.Panel>
        <Tabs.Panel value="payments" pt="md"><PaymentsTab /></Tabs.Panel>
      </Tabs>
      <ActionModal opened={pay} onClose={() => setPay(false)} title="Zahlungseingang buchen">
        <PaymentForm families={families.data?.status === 200 ? families.data.data : []} onClose={() => setPay(false)} />
      </ActionModal>
    </Stack>
  );
}
